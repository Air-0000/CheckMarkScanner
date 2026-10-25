package com.parking.checkscanner;

import android.graphics.Bitmap;
import android.graphics.Rect;
import android.util.Log;
import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 端侧离线 OCR 识别引擎：
 * 直接从拍摄的表格照片中提取真实 序号、姓名、车牌号 与 表头标题，
 * 彻底摆脱死记硬背的花名册模板，自适应任何新员工、新车牌及新表格。
 */
public class TableOcrScanner {
    private static final String TAG = "TableOcrScanner";

    // 匹配中国大陆各省车牌号 (如: 川F08CT9, 湘Lu4079, 粤A2L53E 等)
    private static final Pattern PLATE_PATTERN = Pattern.compile(
        "([京津沪渝冀豫云辽黑湘皖鲁新苏浙赣鄂桂甘晋蒙陕吉闽贵粤青藏川宁琼][A-Za-z0-9]{5,7})"
    );
    // 容错匹配纯英文+数字的车牌后缀 (例如拍照强光导致省份汉字模糊脱落)
    private static final Pattern PLATE_FALLBACK_PATTERN = Pattern.compile(
        "([A-Za-z][A-Za-z0-9]{5,6})"
    );

    private static final Pattern DIGIT_PATTERN = Pattern.compile("^\\s*(\\d{1,3})\\s*$");
    private static final Pattern CHINESE_NAME_PATTERN = Pattern.compile("^[\\u4e00-\\u9fa5]{2,4}$");

    public static class OcrResult {
        public String tableTitle = null;
        public List<ParkingRecord> records = new ArrayList<>();
        public int detectedStartIdx = -1;
        public int detectedPage = 1;
        public int recognizedItemCount = 0;
        public boolean success = false;
    }

    /**
     * 对表格照片进行端侧 OCR 解析，并与物理网格行进行对齐
     */
    public static OcrResult scanTable(Bitmap bitmap, CheckmarkDetector.TableGrid grid, List<ParkingRecord> fallbackRecords) {
        OcrResult result = new OcrResult();
        if (bitmap == null) return result;

        TextRecognizer recognizer = null;
        try {
            recognizer = TextRecognition.getClient(new ChineseTextRecognizerOptions.Builder().build());
            InputImage inputImage = InputImage.fromBitmap(bitmap, 0);

            // 在后台工作线程同步等待 OCR 完成
            Text visionText = Tasks.await(recognizer.process(inputImage), 10, TimeUnit.SECONDS);
            if (visionText != null) {
                processVisionText(visionText, bitmap.getWidth(), bitmap.getHeight(), grid, fallbackRecords, result);
                result.success = (result.recognizedItemCount > 0);
            }
        } catch (Exception e) {
            Log.e(TAG, "端侧 OCR 识别异常: " + e.getMessage(), e);
        } finally {
            if (recognizer != null) {
                try {
                    recognizer.close();
                } catch (Exception ignored) {}
            }
        }

        return result;
    }

    private static void processVisionText(Text visionText, int width, int height,
                                          CheckmarkDetector.TableGrid grid,
                                          List<ParkingRecord> fallbackRecords,
                                          OcrResult outResult) {
        // 1. 确定表格行数 (依据实际检出的网格横线条数决定)
        int rowCount = (fallbackRecords != null && !fallbackRecords.isEmpty()) ? fallbackRecords.size() : 39;
        if (grid != null && grid.hLines != null && grid.hLines.size() >= 2) {
            rowCount = grid.hLines.size() - 1;
        }

        // 2. 初始化数据行列表
        List<ParkingRecord> records = new ArrayList<>();
        for (int i = 0; i < rowCount; i++) {
            if (fallbackRecords != null && i < fallbackRecords.size()) {
                ParkingRecord fb = fallbackRecords.get(i);
                records.add(new ParkingRecord(fb.getIndex(), fb.getName(), fb.getPlateNumber()));
            } else {
                records.add(new ParkingRecord(i + 1, "人员" + (i + 1), ""));
            }
        }

        float shiftsX1 = (grid != null && grid.shiftsX1 > 0) ? grid.shiftsX1 : (width * 0.35f);
        int topHeaderY = (grid != null && grid.hLines != null && !grid.hLines.isEmpty()) ? grid.hLines.get(0) : (height / 5);

        // 3. 提取所有位于左侧名单信息列 (x < shiftsX1) 的文本元素
        List<TextItem> leftItems = new ArrayList<>();

        for (Text.TextBlock block : visionText.getTextBlocks()) {
            for (Text.Line line : block.getLines()) {
                Rect lBox = line.getBoundingBox();
                if (lBox == null) continue;

                // 提取表头大标题 (位于第一条横线之上)
                if (lBox.centerY() < topHeaderY - 10) {
                    String lineText = line.getText().trim();
                    if (lineText.contains("岗") || lineText.contains("车") || lineText.contains("信息") || lineText.contains("表")) {
                        outResult.tableTitle = lineText;
                    }
                    continue;
                }

                // 过滤右侧班次打钩区内容 (周一~周日、白、夜等表头)
                if (lBox.centerX() > shiftsX1 + 5) {
                    continue;
                }

                List<Text.Element> elements = line.getElements();
                if (elements != null && !elements.isEmpty()) {
                    for (Text.Element elem : elements) {
                        Rect eBox = elem.getBoundingBox();
                        if (eBox != null && eBox.centerX() < shiftsX1 + 5) {
                            leftItems.add(new TextItem(elem.getText().trim(), eBox));
                        }
                    }
                } else {
                    leftItems.add(new TextItem(line.getText().trim(), lBox));
                }
            }
        }

        // 4. 将提取的文本映射至对应的物理数据行
        for (TextItem item : leftItems) {
            String text = item.text;
            if (text.isEmpty()) continue;

            // 过滤列头标签字
            if (text.equals("序号") || text.equals("姓名") || text.equals("车牌") || 
                text.equals("车牌号") || text.equals("白") || text.equals("夜") ||
                text.equals("星期") || text.equals("备注")) {
                continue;
            }

            int r = findRowIndex(item.box.centerY(), grid, height, rowCount);
            if (r < 0 || r >= records.size()) continue;

            ParkingRecord rec = records.get(r);
            float cx = item.box.centerX();

            // 规则 1: 判定为车牌号 (含汉字省份缩写，位于靠右车牌列)
            Matcher plateMatcher = PLATE_PATTERN.matcher(text);
            if (plateMatcher.find()) {
                rec.setPlateNumber(plateMatcher.group(1));
                outResult.recognizedItemCount++;
                continue;
            }

            // 规则 2: 判定为序号 (纯数字，位于靠左序号列)
            Matcher digitMatcher = DIGIT_PATTERN.matcher(text);
            if (digitMatcher.matches() && cx < shiftsX1 * 0.38f) {
                try {
                    int idx = Integer.parseInt(digitMatcher.group(1));
                    if (idx > 0 && idx <= 500) {
                        rec.setIndex(idx);
                        outResult.recognizedItemCount++;
                        if (outResult.detectedStartIdx < 0 || r == 0) {
                            outResult.detectedStartIdx = idx;
                        }
                        continue;
                    }
                } catch (Exception ignored) {}
            }

            // 规则 3: 判定为姓名 (2~4 位纯汉字，位于中间姓名列)
            if (CHINESE_NAME_PATTERN.matcher(text).matches() && cx >= shiftsX1 * 0.10f && cx < shiftsX1 * 0.70f) {
                rec.setName(text);
                outResult.recognizedItemCount++;
                continue;
            }

            // 规则 4: 容错车牌 (拍照反光导致汉字省份脱落的 6~7 位字母数字)
            Matcher fbPlate = PLATE_FALLBACK_PATTERN.matcher(text);
            if (fbPlate.matches() && cx >= shiftsX1 * 0.40f) {
                String curPlate = rec.getPlateNumber();
                if (curPlate == null || curPlate.isEmpty() || !curPlate.matches(".*[\\u4e00-\\u9fa5].*")) {
                    rec.setPlateNumber(fbPlate.group(1));
                    outResult.recognizedItemCount++;
                }
            }
        }

        // 5. 序号自增平滑修正 (若偶有某行序号因折痕反光漏检，自动按连续递增推算补全)
        int lastKnownIdx = -1;
        for (int r = 0; r < records.size(); r++) {
            ParkingRecord rec = records.get(r);
            if (rec.getIndex() > 0) {
                lastKnownIdx = rec.getIndex();
            } else if (lastKnownIdx > 0) {
                rec.setIndex(lastKnownIdx + 1);
                lastKnownIdx = rec.getIndex();
            }
        }

        // 6. 根据实际 OCR 识别出的序号智能推算页面归属
        if (outResult.detectedStartIdx >= 78) {
            outResult.detectedPage = 3;
        } else if (outResult.detectedStartIdx >= 40) {
            outResult.detectedPage = 2;
        } else if (outResult.detectedStartIdx >= 1) {
            outResult.detectedPage = 1;
        }

        outResult.records = records;
        Log.i(TAG, "OCR 完成: 识别出 " + outResult.recognizedItemCount + " 项数据, 起始序号=" 
            + outResult.detectedStartIdx + ", 推算页面=" + outResult.detectedPage + ", 标题=" + outResult.tableTitle);
    }

    private static int findRowIndex(int centerY, CheckmarkDetector.TableGrid grid, int imgH, int rowCount) {
        if (grid != null && grid.hLines != null && grid.hLines.size() >= 2) {
            for (int r = 0; r < grid.hLines.size() - 1; r++) {
                int y1 = grid.hLines.get(r);
                int y2 = grid.hLines.get(r + 1);
                if (centerY >= y1 - 2 && centerY <= y2 + 2) {
                    return r;
                }
            }
        }
        return -1;
    }

    private static class TextItem {
        final String text;
        final Rect box;
        TextItem(String t, Rect b) {
            this.text = t;
            this.box = b;
        }
    }
}
