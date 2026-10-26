package com.parking.checkscanner;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.RectF;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.FileProvider;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 训练数据沉淀与自适应自优化学习引擎 (Training Dataset Collector & Self-Optimization Engine)
 * 核心机制：
 * 1. 采集用户日常手动作出的修正（漏检补勾、误检取消）以及最终确认导出的表格切片；
 * 2. 自动裁剪单元格小图、提取笔画几何与对比度特征，结构化存入本地 dataset_manifest.jsonl；
 * 3. 基于累积的数据集进行在线自校准 (On-Device Calibration)，动态调整算法阈值以贴合当前用户的纸张、光线、笔迹深浅；
 * 4. 支持一键打包为 Dataset ZIP 压缩包，方便通过微信/邮件直接回传给算法开发者持续优化迭代模型。
 */
public class TrainingDataCollector {

    private static final String TAG = "TrainingDataCollector";
    private static final String PREF_NAME = "training_calibration";
    public static final String KEY_CALIBRATED_MIN_CONTRAST = "calibrated_min_contrast";
    public static final String KEY_CALIBRATED_NOISE_FLOOR = "calibrated_noise_floor";
    public static final String KEY_TOTAL_CORRECTIONS = "total_corrections";

    private static TrainingDataCollector instance;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public static synchronized TrainingDataCollector getInstance() {
        if (instance == null) {
            instance = new TrainingDataCollector();
        }
        return instance;
    }

    public static class SampleRecord {
        public String id;
        public String timestamp;
        public String imageName;
        public int label; // 1: 打钩 (Checked), 0: 空白/杂斑 (Unchecked)
        public String correctionType; // "USER_MANUAL_CHECK", "USER_MANUAL_UNCHECK", "AUTO_VERIFIED"
        public int row;
        public int col;
        public String personName;
        public String shiftName;
        public String excelCell;
        public int cellWidth;
        public int cellHeight;
        public int contrast;
        public int strokeCount;

        public JSONObject toJson() {
            JSONObject obj = new JSONObject();
            try {
                obj.put("id", id);
                obj.put("timestamp", timestamp);
                obj.put("image_name", imageName);
                obj.put("label", label);
                obj.put("correction_type", correctionType);
                obj.put("row", row);
                obj.put("col", col);
                obj.put("person_name", personName != null ? personName : "");
                obj.put("shift_name", shiftName != null ? shiftName : "");
                obj.put("excel_cell", excelCell != null ? excelCell : "");
                obj.put("cell_width", cellWidth);
                obj.put("cell_height", cellHeight);
                obj.put("contrast", contrast);
                obj.put("stroke_count", strokeCount);
            } catch (Exception ignored) {}
            return obj;
        }

        public static SampleRecord fromJson(String jsonStr) {
            try {
                JSONObject obj = new JSONObject(jsonStr);
                SampleRecord rec = new SampleRecord();
                rec.id = obj.optString("id", "");
                rec.timestamp = obj.optString("timestamp", "");
                rec.imageName = obj.optString("image_name", "");
                rec.label = obj.optInt("label", 0);
                rec.correctionType = obj.optString("correction_type", "");
                rec.row = obj.optInt("row", 0);
                rec.col = obj.optInt("col", 0);
                rec.personName = obj.optString("person_name", "");
                rec.shiftName = obj.optString("shift_name", "");
                rec.excelCell = obj.optString("excel_cell", "");
                rec.cellWidth = obj.optInt("cell_width", 0);
                rec.cellHeight = obj.optInt("cell_height", 0);
                rec.contrast = obj.optInt("contrast", 0);
                rec.strokeCount = obj.optInt("stroke_count", 0);
                return rec;
            } catch (Exception e) {
                return null;
            }
        }
    }

    public static class DatasetStats {
        public int totalSamples = 0;
        public int positiveChecks = 0;
        public int negativeBlanks = 0;
        public int manualCorrections = 0;
        public int calibratedMinContrast = 25;
        public boolean isCalibrated = false;
    }

    private File getDatasetDir(Context context) {
        File dir = context.getExternalFilesDir("training_dataset");
        if (dir == null) {
            dir = new File(context.getFilesDir(), "training_dataset");
        }
        if (!dir.exists()) {
            dir.mkdirs();
        }
        File imgDir = new File(dir, "images");
        if (!imgDir.exists()) {
            imgDir.mkdirs();
        }
        return dir;
    }

    private File getManifestFile(Context context) {
        return new File(getDatasetDir(context), "dataset_manifest.jsonl");
    }

    /**
     * 记录用户的手动修正（如点击照片单元格补勾或取消）
     */
    public void recordManualCorrection(Context context, Bitmap fullBitmap, RectF cellRect, 
                                       int row, int col, String personName, String shiftName, 
                                       String excelCell, boolean isCheckedNow) {
        if (context == null || fullBitmap == null || cellRect == null) return;

        executor.execute(() -> {
            try {
                File dir = getDatasetDir(context);
                File imgDir = new File(dir, "images");
                String timeStr = new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.getDefault()).format(new Date());
                String id = "sample_" + timeStr + "_r" + row + "_c" + col;
                int label = isCheckedNow ? 1 : 0;
                String imgName = "crop_" + timeStr + "_r" + row + "_c" + col + "_label" + label + ".png";
                File imgFile = new File(imgDir, imgName);

                // 单元格裁剪（外扩 2 像素保留边框上下文）
                int bx = Math.max(0, Math.round(cellRect.left) - 2);
                int by = Math.max(0, Math.round(cellRect.top) - 2);
                int bw = Math.min(fullBitmap.getWidth() - bx, Math.round(cellRect.width()) + 4);
                int bh = Math.min(fullBitmap.getHeight() - by, Math.round(cellRect.height()) + 4);

                if (bw > 2 && bh > 2) {
                    Bitmap crop = Bitmap.createBitmap(fullBitmap, bx, by, bw, bh);
                    FileOutputStream fos = new FileOutputStream(imgFile);
                    crop.compress(Bitmap.CompressFormat.PNG, 100, fos);
                    fos.flush();
                    fos.close();
                    crop.recycle();
                }

                // 计算图像基本对比度与笔画像素数
                int contrast = 0;
                int strokeCount = 0;
                int cx1 = Math.max(0, Math.round(cellRect.left));
                int cy1 = Math.max(0, Math.round(cellRect.top));
                int cx2 = Math.min(fullBitmap.getWidth(), Math.round(cellRect.right));
                int cy2 = Math.min(fullBitmap.getHeight(), Math.round(cellRect.bottom));
                int totalPix = (cx2 - cx1) * (cy2 - cy1);

                if (totalPix > 0) {
                    int[] hist = new int[256];
                    int minG = 255;
                    for (int y = cy1; y < cy2; y++) {
                        for (int x = cx1; x < cx2; x++) {
                            int p = fullBitmap.getPixel(x, y);
                            int r = (p >> 16) & 0xff;
                            int g = (p >> 8) & 0xff;
                            int b = p & 0xff;
                            int gray = (r * 299 + g * 587 + b * 114) / 1000;
                            hist[gray]++;
                            if (gray < minG) minG = gray;
                        }
                    }
                    int acc = 0, p85 = 255;
                    for (int g = 0; g < 256; g++) {
                        acc += hist[g];
                        if (acc >= Math.round(totalPix * 0.85f)) {
                            p85 = g;
                            break;
                        }
                    }
                    contrast = p85 - minG;
                    int strokeThresh = p85 - 20;
                    for (int g = 0; g < strokeThresh; g++) {
                        strokeCount += hist[g];
                    }
                }

                SampleRecord rec = new SampleRecord();
                rec.id = id;
                rec.timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date());
                rec.imageName = imgName;
                rec.label = label;
                rec.correctionType = isCheckedNow ? "USER_MANUAL_CHECK" : "USER_MANUAL_UNCHECK";
                rec.row = row;
                rec.col = col;
                rec.personName = personName;
                rec.shiftName = shiftName;
                rec.excelCell = excelCell;
                rec.cellWidth = Math.round(cellRect.width());
                rec.cellHeight = Math.round(cellRect.height());
                rec.contrast = contrast;
                rec.strokeCount = strokeCount;

                // 写入 manifest.jsonl
                File manifest = getManifestFile(context);
                FileWriter fw = new FileWriter(manifest, true);
                fw.write(rec.toJson().toString() + "\n");
                fw.flush();
                fw.close();

                // 更新纠错计数
                SharedPreferences sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
                int count = sp.getInt(KEY_TOTAL_CORRECTIONS, 0) + 1;
                sp.edit().putInt(KEY_TOTAL_CORRECTIONS, count).apply();

                Log.d(TAG, "已累计手动修正样本: " + id + ", label=" + label + ", contrast=" + contrast);

                // 触发自适应校准判定
                autoCalibrateIfThresholdReached(context);

            } catch (Exception e) {
                Log.e(TAG, "记录修正样本异常: " + e.getMessage(), e);
            }
        });
    }

    /**
     * 批量沉淀整页已核对通过的表格样本（在导出 Excel 时调用）
     */
    public void recordBatchVerifiedPage(Context context, Bitmap fullBitmap, CheckmarkDetector.TableGrid grid, List<ParkingRecord> records) {
        if (context == null || fullBitmap == null || grid == null || records == null) return;

        executor.execute(() -> {
            try {
                File dir = getDatasetDir(context);
                File imgDir = new File(dir, "images");
                File manifest = getManifestFile(context);
                FileWriter fw = new FileWriter(manifest, true);

                int savedCount = 0;
                String timeStr = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
                int maxRow = Math.min(records.size(), grid.hLines.size() - 1);

                for (int r = 0; r < maxRow; r++) {
                    ParkingRecord rec = records.get(r);
                    for (int c = 0; c < 14; c++) {
                        boolean isChecked = rec.isShiftChecked(c);
                        // 优先采集正样本打钩或关键修改项
                        if (!isChecked && (r % 3 != 0)) continue; // 负样本按步长抽样，避免空白样本过多失衡

                        RectF rect = grid.getCellRect(r, c, 0, 0, fullBitmap.getWidth(), fullBitmap.getHeight());
                        if (rect == null) continue;

                        int bx = Math.max(0, Math.round(rect.left) - 2);
                        int by = Math.max(0, Math.round(rect.top) - 2);
                        int bw = Math.min(fullBitmap.getWidth() - bx, Math.round(rect.width()) + 4);
                        int bh = Math.min(fullBitmap.getHeight() - by, Math.round(rect.height()) + 4);
                        if (bw <= 2 || bh <= 2) continue;

                        int label = isChecked ? 1 : 0;
                        String imgName = "batch_" + timeStr + "_r" + r + "_c" + c + "_label" + label + ".png";
                        File imgFile = new File(imgDir, imgName);

                        Bitmap crop = Bitmap.createBitmap(fullBitmap, bx, by, bw, bh);
                        FileOutputStream fos = new FileOutputStream(imgFile);
                        crop.compress(Bitmap.CompressFormat.PNG, 95, fos);
                        fos.flush();
                        fos.close();
                        crop.recycle();

                        SampleRecord sample = new SampleRecord();
                        sample.id = "batch_" + timeStr + "_r" + r + "_c" + c;
                        sample.timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date());
                        sample.imageName = imgName;
                        sample.label = label;
                        sample.correctionType = "EXPORT_VERIFIED";
                        sample.row = r;
                        sample.col = c;
                        sample.personName = rec.getName();
                        sample.shiftName = ShiftConstants.SHIFT_NAMES[c];
                        sample.excelCell = rec.getExcelCell(c);
                        sample.cellWidth = Math.round(rect.width());
                        sample.cellHeight = Math.round(rect.height());
                        sample.contrast = 0;
                        sample.strokeCount = 0;

                        fw.write(sample.toJson().toString() + "\n");
                        savedCount++;
                    }
                }

                fw.flush();
                fw.close();
                Log.d(TAG, "已批量归档核对样本 " + savedCount + " 组");

            } catch (Exception e) {
                Log.e(TAG, "批量沉淀样本异常: " + e.getMessage(), e);
            }
        });
    }

    /**
     * 在样本写入线程上统计并回调 (回调在该后台线程执行)，
     * 保证排在之前提交的样本写入之后，计数不会滞后一次
     */
    public void getStatsAsync(Context context, java.util.function.Consumer<DatasetStats> callback) {
        executor.execute(() -> callback.accept(getStats(context)));
    }

    /**
     * 获取当前本地累积的数据集统计信息
     */
    public DatasetStats getStats(Context context) {
        DatasetStats stats = new DatasetStats();
        if (context == null) return stats;

        File manifest = getManifestFile(context);
        if (!manifest.exists()) return stats;

        SharedPreferences sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        stats.calibratedMinContrast = sp.getInt(KEY_CALIBRATED_MIN_CONTRAST, 25);
        stats.isCalibrated = sp.getBoolean("is_calibrated", false);

        BufferedReader br = null;
        try {
            br = new BufferedReader(new FileReader(manifest));
            String line;
            while ((line = br.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                SampleRecord rec = SampleRecord.fromJson(line);
                if (rec != null) {
                    stats.totalSamples++;
                    if (rec.label == 1) {
                        stats.positiveChecks++;
                    } else {
                        stats.negativeBlanks++;
                    }
                    if (rec.correctionType.startsWith("USER_MANUAL")) {
                        stats.manualCorrections++;
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "读取统计异常: " + e.getMessage());
        } finally {
            try {
                if (br != null) br.close();
            } catch (Exception ignored) {}
        }
        return stats;
    }

    /**
     * 基于累积样本进行端侧在线算法自校准 (On-Device Self-Optimization)
     */
    public String calibrateThresholds(Context context) {
        if (context == null) return "上下文无效";

        DatasetStats stats = getStats(context);
        if (stats.totalSamples < 3) {
            return "当前沉淀样本数不足 (需至少 3 组样本)，请在日常使用中继续操作或修正，系统将自动自学习。";
        }

        File manifest = getManifestFile(context);
        List<SampleRecord> positives = new ArrayList<>();
        List<SampleRecord> negatives = new ArrayList<>();

        try (BufferedReader br = new BufferedReader(new FileReader(manifest))) {
            String line;
            while ((line = br.readLine()) != null) {
                SampleRecord rec = SampleRecord.fromJson(line);
                if (rec != null) {
                    if (rec.label == 1 && rec.contrast > 0) positives.add(rec);
                    else if (rec.label == 0 && rec.strokeCount > 0) negatives.add(rec);
                }
            }
        } catch (Exception e) {
            return "分析样本集失败: " + e.getMessage();
        }

        // 计算正样本的对比度分布
        int optimizedMinContrast = 25; // 默认
        if (!positives.isEmpty()) {
            int minPContrast = Integer.MAX_VALUE;
            for (SampleRecord p : positives) {
                if (p.contrast < minPContrast) minPContrast = p.contrast;
            }
            // 若用户打钩多为浅淡铅笔(对比度在18~24之间)，算法主动下潜阈值以完美契合用户笔迹
            optimizedMinContrast = Math.max(16, Math.min(26, minPContrast - 2));
        }

        SharedPreferences sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        sp.edit()
          .putInt(KEY_CALIBRATED_MIN_CONTRAST, optimizedMinContrast)
          .putBoolean("is_calibrated", true)
          .apply();

        String resultMsg = String.format(Locale.getDefault(),
            "✅ 算法自校准成功！\n" +
            "• 已分析真实样本：%d 组 (打钩 %d, 空白/杂斑 %d)\n" +
            "• 人工纠错沉淀：%d 次\n" +
            "• 笔迹对比度阈值已自动适配为：%d (完美匹配您的书写深浅与纸质)",
            stats.totalSamples, stats.positiveChecks, stats.negativeBlanks, stats.manualCorrections, optimizedMinContrast);

        Log.d(TAG, resultMsg);
        return resultMsg;
    }

    private void autoCalibrateIfThresholdReached(Context context) {
        SharedPreferences sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        int corrections = sp.getInt(KEY_TOTAL_CORRECTIONS, 0);
        // 每当用户累计修正满 5、15、30 次时，自动执行静默自校准
        if (corrections == 5 || corrections == 15 || corrections == 30) {
            calibrateThresholds(context);
        }
    }

    public static int getCalibratedMinContrast(Context context) {
        if (context == null) return 25;
        SharedPreferences sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        return sp.getInt(KEY_CALIBRATED_MIN_CONTRAST, 25);
    }

    /**
     * 一键打包全部训练数据集为 ZIP 压缩包并调起系统分享
     */
    public void exportDatasetZip(Activity activity) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;

        StyledToast.show(activity, "正在打包训练数据集，请稍候...", Toast.LENGTH_SHORT);

        executor.execute(() -> {
            try {
                File dir = getDatasetDir(activity);
                File manifest = getManifestFile(activity);
                if (!manifest.exists() || manifest.length() == 0) {
                    mainHandler.post(() -> StyledToast.show(activity, "暂无训练样本数据，请先拍照或进行日常表格核对", Toast.LENGTH_SHORT));
                    return;
                }

                String timeStr = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
                File cacheDir = activity.getExternalCacheDir();
                if (cacheDir == null) cacheDir = activity.getCacheDir();
                File zipFile = new File(cacheDir, "CheckMark_TrainingData_" + timeStr + ".zip");
                if (zipFile.exists()) zipFile.delete();

                ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zipFile));

                // 写入 manifest
                addFileToZip(zos, manifest, "dataset_manifest.jsonl");

                // 写入所有裁剪图片
                File imgDir = new File(dir, "images");
                File[] imgs = imgDir.listFiles();
                int imgCount = 0;
                if (imgs != null) {
                    for (File img : imgs) {
                        if (img.isFile() && img.getName().endsWith(".png")) {
                            addFileToZip(zos, img, "images/" + img.getName());
                            imgCount++;
                        }
                    }
                }

                zos.finish();
                zos.close();

                final int finalImgCount = imgCount;
                mainHandler.post(() -> {
                    if (activity.isFinishing() || activity.isDestroyed()) return;

                    Uri zipUri = FileProvider.getUriForFile(
                        activity,
                        activity.getPackageName() + ".fileprovider",
                        zipFile
                    );

                    Intent shareIntent = new Intent(Intent.ACTION_SEND);
                    shareIntent.setType("application/zip");
                    shareIntent.putExtra(Intent.EXTRA_STREAM, zipUri);
                    shareIntent.putExtra(Intent.EXTRA_SUBJECT, "CheckMark 打钩识别训练数据集 (" + finalImgCount + "组样本)");
                    shareIntent.putExtra(Intent.EXTRA_TEXT, "这是来自现场实际采集的打钩与人工纠错训练数据集，包含 " + finalImgCount + " 组样本切片与标注，可用于优化视觉算法模型。");
                    shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

                    activity.startActivity(Intent.createChooser(shareIntent, "分享/导出训练数据集 (ZIP)"));
                });

            } catch (Exception e) {
                Log.e(TAG, "打包数据集失败: " + e.getMessage(), e);
                mainHandler.post(() -> StyledToast.show(activity, "打包数据集失败: " + e.getMessage(), Toast.LENGTH_LONG));
            }
        });
    }

    private void addFileToZip(ZipOutputStream zos, File file, String entryName) throws IOException {
        ZipEntry ze = new ZipEntry(entryName);
        zos.putNextEntry(ze);
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] buf = new byte[8192];
            int len;
            while ((len = fis.read(buf)) != -1) {
                zos.write(buf, 0, len);
            }
        }
        zos.closeEntry();
    }

    /**
     * 清空训练数据集
     */
    public void clearDataset(Context context, Runnable onCleared) {
        executor.execute(() -> {
            try {
                File dir = getDatasetDir(context);
                deleteRecursively(dir);
                SharedPreferences sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
                sp.edit().clear().apply();
                mainHandler.post(() -> {
                    if (onCleared != null) onCleared.run();
                });
            } catch (Exception e) {
                Log.e(TAG, "清空样本库异常: " + e.getMessage(), e);
            }
        });
    }

    private void deleteRecursively(File fileOrDir) {
        if (fileOrDir != null && fileOrDir.isDirectory()) {
            File[] children = fileOrDir.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursively(child);
                }
            }
        }
        if (fileOrDir != null) fileOrDir.delete();
    }
}
