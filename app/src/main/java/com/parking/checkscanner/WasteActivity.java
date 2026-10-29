package com.parking.checkscanner;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.Log;
import android.view.GestureDetector;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;
import com.google.android.material.textfield.TextInputLayout;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 废料登记表识别：拍照 / 相册导入手写《废料登记表》，按行识别后对照照片核对修改，导出 Excel。
 * 每张照片一页，导出时各页的行按页顺序合并。
 */
public class WasteActivity extends AppCompatActivity {

    private static final String TAG = "WasteActivity";
    private static final int MAX_PAGES = 20;
    private static final String PREF_MODE = "app_mode";

    /** 上次退出时是否停在废料登记表 (MainActivity 启动时据此直接打开本页) */
    public static boolean isLastMode(Context context) {
        return context.getSharedPreferences(PREF_MODE, MODE_PRIVATE).getBoolean("waste", false);
    }

    private void rememberMode(boolean waste) {
        getSharedPreferences(PREF_MODE, MODE_PRIVATE).edit().putBoolean("waste", waste).apply();
    }

    private static class WastePage {
        Bitmap originalBitmap;
        Bitmap annotatedBitmap;
        WasteSheetScanner.Grid grid;
        List<WasteRecord> records = new ArrayList<>();
        String error; // 识别失败原因 (没找到表头等)
    }

    private final List<WastePage> pages = new ArrayList<>();
    // 当前查看的页 (下标)；等于 pages.size() 表示「＋ 新页」
    private int currentPage = 0;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private boolean isBusy = false;
    private Uri cameraImageUri = null;
    private WasteCategories categories;

    private MaterialToolbar toolbar;
    private MaterialButton btnCamera, btnGallery, btnExport;
    private ChipGroup chipGroupPages;
    private HorizontalScrollView scrollPageChips;
    private LinearLayout layoutPageStatus, layoutEmptySteps, llRows;
    private TextView tvStatusBadge, tvPreviewTip, tvPreviewEmpty, btnAddRow;
    private LinearProgressIndicator progressBusy;
    private ImageView ivPreview;

    private ActivityResultLauncher<String> galleryLauncher;
    private ActivityResultLauncher<Uri> cameraLauncher;
    private ActivityResultLauncher<String> cameraPermissionLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_waste);
        categories = new WasteCategories(this);
        rememberMode(true);

        toolbar = findViewById(R.id.toolbar);
        btnCamera = findViewById(R.id.btn_camera);
        btnGallery = findViewById(R.id.btn_gallery);
        btnExport = findViewById(R.id.btn_export);
        chipGroupPages = findViewById(R.id.chip_group_pages);
        scrollPageChips = findViewById(R.id.scroll_page_chips);
        layoutPageStatus = findViewById(R.id.layout_page_status);
        layoutEmptySteps = findViewById(R.id.layout_empty_steps);
        llRows = findViewById(R.id.ll_rows);
        tvStatusBadge = findViewById(R.id.tv_status_badge);
        tvPreviewTip = findViewById(R.id.tv_preview_tip);
        tvPreviewEmpty = findViewById(R.id.tv_preview_empty);
        btnAddRow = findViewById(R.id.btn_add_row);
        progressBusy = findViewById(R.id.progress_busy);
        ivPreview = findViewById(R.id.iv_preview);

        setupLaunchers();
        setupEvents();
        refreshUi();
    }

    /** 主动返回停车打钩 (返回键 / 左上角箭头) 时，下次启动不再直接进入本页 */
    @Override
    public void finish() {
        rememberMode(false);
        super.finish();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
        // 模型约 11MB，离开本页即释放 (在识别线程上关，避免关在识别途中)
        new Thread(() -> {
            try {
                executor.awaitTermination(30, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {}
            if (handwritingRecognizer != null) handwritingRecognizer.close();
        }).start();
    }

    // 手写识别模型首次识别时在后台线程加载，之后各张照片复用
    private PaddleTextRecognizer handwritingRecognizer;

    private PaddleTextRecognizer getHandwritingRecognizer() throws Exception {
        if (handwritingRecognizer == null) handwritingRecognizer = new PaddleTextRecognizer(this);
        return handwritingRecognizer;
    }

    private void setupLaunchers() {
        galleryLauncher = registerForActivityResult(new ActivityResultContracts.GetMultipleContents(), uris -> {
            if (uris != null && !uris.isEmpty()) importImages(uris);
        });
        cameraLauncher = registerForActivityResult(new ActivityResultContracts.TakePicture(), success -> {
            if (success && cameraImageUri != null) importImages(Arrays.asList(cameraImageUri));
        });
        cameraPermissionLauncher = registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
            if (granted) {
                startCameraCapture();
            } else {
                StyledToast.show(this, "需要相机权限拍摄纸张表格", Toast.LENGTH_SHORT);
            }
        });
    }

    private void setupEvents() {
        toolbar.setNavigationOnClickListener(v -> finish());
        toolbar.setOnMenuItemClickListener(this::onMenuItemClick);

        btnCamera.setOnClickListener(v -> {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                startCameraCapture();
            } else {
                cameraPermissionLauncher.launch(Manifest.permission.CAMERA);
            }
        });
        btnGallery.setOnClickListener(v -> {
            if (!rejectIfBusy()) galleryLauncher.launch("image/*");
        });
        btnExport.setOnClickListener(v -> showExportSheet());
        btnAddRow.setOnClickListener(v -> {
            if (rejectIfBusy() || currentPage >= pages.size()) return;
            showRowEditor(pages.get(currentPage), null);
        });

        // 点照片上的某一行 → 修改该行
        GestureDetector detector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                handleImageTouch(e.getX(), e.getY());
                return true;
            }
        });
        ivPreview.setOnTouchListener((v, event) -> {
            detector.onTouchEvent(event);
            return false;
        });
    }

    private boolean onMenuItemClick(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_edit_categories) {
            showCategoryEditor();
        } else if (id == R.id.action_clear_all) {
            if (rejectIfBusy() || pages.isEmpty()) return true;
            new AlertDialog.Builder(this)
                .setTitle("清空全部页？")
                .setMessage("已导入的 " + pages.size() + " 页照片与识别结果都会清除")
                .setPositiveButton("清空", (d, w) -> {
                    pages.clear();
                    currentPage = 0;
                    refreshUi();
                })
                .setNegativeButton("取消", null)
                .show();
        } else {
            return false;
        }
        return true;
    }

    private boolean rejectIfBusy() {
        if (isBusy) StyledToast.show(this, "正在识别中，请稍候...", Toast.LENGTH_SHORT);
        return isBusy;
    }

    private void setBusy(boolean busy, String text) {
        isBusy = busy;
        progressBusy.setVisibility(busy ? View.VISIBLE : View.INVISIBLE);
        btnCamera.setEnabled(!busy);
        btnGallery.setEnabled(!busy);
        btnExport.setEnabled(!busy);
        if (busy) {
            layoutPageStatus.setVisibility(View.VISIBLE);
            tvStatusBadge.setText(text);
            tvPreviewTip.setText("请稍候...");
            applyBadgeStyle(true);
        } else {
            refreshUi();
        }
    }

    private void applyBadgeStyle(boolean alert) {
        tvStatusBadge.setBackgroundResource(alert ? R.drawable.bg_tag_hazard : R.drawable.bg_tag_outline);
        tvStatusBadge.setTextColor(ContextCompat.getColor(this, alert ? R.color.on_hazard : R.color.text_main));
    }

    // ===== 页面与列表 =====

    /** 按当前数据刷新页签、状态行、照片与行列表 */
    private void refreshUi() {
        boolean any = !pages.isEmpty();
        currentPage = Math.max(0, Math.min(currentPage, pages.size()));

        // 页签：已导入的各页 + 「＋ 新页」(还没满 20 页时)
        chipGroupPages.removeAllViews();
        LayoutInflater inflater = getLayoutInflater();
        int chipCount = pages.size() + (pages.size() < MAX_PAGES ? 1 : 0);
        Chip selected = null;
        for (int i = 0; i < chipCount; i++) {
            final int idx = i;
            Chip chip = (Chip) inflater.inflate(R.layout.chip_page, chipGroupPages, false);
            chip.setId(View.generateViewId());
            if (i < pages.size()) {
                WastePage p = pages.get(i);
                chip.setText("第" + (i + 1) + "页 (" + (p.error != null ? "未识别" : p.records.size() + "行") + ")");
            } else {
                chip.setText("＋ 新页");
            }
            chip.setOnClickListener(v -> {
                currentPage = idx;
                refreshUi();
            });
            chipGroupPages.addView(chip);
            if (i == currentPage) selected = chip;
        }
        if (selected != null) {
            selected.setChecked(true);
            final Chip target = selected;
            target.post(() -> scrollPageChips.smoothScrollTo(
                Math.max(0, target.getLeft() - scrollPageChips.getPaddingStart()), 0));
        }
        scrollPageChips.setVisibility(any ? View.VISIBLE : View.GONE);
        layoutPageStatus.setVisibility(any ? View.VISIBLE : View.GONE);
        layoutEmptySteps.setVisibility(any ? View.GONE : View.VISIBLE);

        WastePage page = currentPage < pages.size() ? pages.get(currentPage) : null;
        ivPreview.setVisibility(page != null ? View.VISIBLE : View.GONE);
        tvPreviewEmpty.setVisibility(any && page == null ? View.VISIBLE : View.GONE);
        btnAddRow.setVisibility(page != null ? View.VISIBLE : View.GONE);
        if (page != null) {
            ivPreview.setImageBitmap(page.annotatedBitmap != null ? page.annotatedBitmap : page.originalBitmap);
        }

        // 状态行
        boolean alert = false;
        if (page == null) {
            tvStatusBadge.setText("新页");
            tvPreviewTip.setText("拍照或从相册导入，放到这一页");
        } else if (page.error != null) {
            alert = true;
            tvStatusBadge.setText("未识别");
            tvPreviewTip.setText(page.error + "；也可以点下方「手动添加一行」");
        } else {
            int unsure = 0;
            for (WasteRecord r : page.records) if (r.hasUncertain()) unsure++;
            alert = unsure > 0;
            tvStatusBadge.setText(page.records.size() + " 行" + (unsure > 0 ? " · " + unsure + " 行待核对" : ""));
            tvPreviewTip.setText(unsure > 0 ? "黄底格子没认准：点照片上的行或下方列表修改" : "已核对，可导出");
        }
        if (!isBusy) applyBadgeStyle(alert);

        buildRowList(page);
    }

    private void buildRowList(WastePage page) {
        llRows.removeAllViews();
        if (page == null) return;
        LayoutInflater inflater = getLayoutInflater();
        for (int i = 0; i < page.records.size(); i++) {
            WasteRecord rec = page.records.get(i);
            View row = inflater.inflate(R.layout.item_waste_row, llRows, false);
            TextView no = row.findViewById(R.id.tv_row_no);
            no.setText(String.valueOf(i + 1));
            boolean unsure = rec.hasUncertain();
            no.setBackgroundResource(unsure ? R.drawable.bg_tag_hazard : R.drawable.bg_tag_outline);
            no.setTextColor(ContextCompat.getColor(this, unsure ? R.color.on_hazard : R.color.text_main));
            ((TextView) row.findViewById(R.id.tv_row_main)).setText(rec.summary());
            ((TextView) row.findViewById(R.id.tv_row_sub)).setText(detailLine(rec));
            row.setOnClickListener(v -> {
                if (!rejectIfBusy()) showRowEditor(page, rec);
            });
            llRows.addView(row);
        }
    }

    /** 列表第二行：地点 / 跟进 / 时间 / 保安，待核对的列名列在最后 */
    private static String detailLine(WasteRecord rec) {
        List<String> parts = new ArrayList<>();
        if (!rec.get(WasteRecord.COL_LOCATION).isEmpty()) parts.add(rec.get(WasteRecord.COL_LOCATION));
        String follow = (rec.get(WasteRecord.COL_FOLLOWER) + " " + rec.get(WasteRecord.COL_TIME)).trim();
        if (!follow.isEmpty()) parts.add("跟进 " + follow);
        if (!rec.get(WasteRecord.COL_GUARD).isEmpty()) parts.add("保安 " + rec.get(WasteRecord.COL_GUARD));
        List<String> unsure = new ArrayList<>();
        for (int c = 0; c < WasteRecord.COL_COUNT; c++) {
            if (rec.isUncertain(c)) unsure.add(WasteRecord.COLUMN_NAMES[c].replaceAll("\\(.*\\)|/.*", ""));
        }
        String s = String.join(" · ", parts);
        if (!unsure.isEmpty()) s += (s.isEmpty() ? "" : "\n") + "待核对：" + String.join("、", unsure);
        return s;
    }

    private void handleImageTouch(float touchX, float touchY) {
        if (isBusy || currentPage >= pages.size()) return;
        WastePage page = pages.get(currentPage);
        if (page.grid == null || ivPreview.getDrawable() == null) return;
        float[] values = new float[9];
        ivPreview.getImageMatrix().getValues(values);
        float sx = values[Matrix.MSCALE_X];
        float sy = values[Matrix.MSCALE_Y];
        if (sx <= 0 || sy <= 0) return;
        float x = (touchX - values[Matrix.MTRANS_X]) / sx;
        float y = (touchY - values[Matrix.MTRANS_Y]) / sy;
        int r = page.grid.rowAt(x, y);
        if (r < 0) return;
        for (WasteRecord rec : page.records) {
            if (rec.photoRow == r) {
                showRowEditor(page, rec);
                return;
            }
        }
        StyledToast.show(this, "这一行没有识别出内容，可点下方「手动添加一行」", Toast.LENGTH_SHORT);
    }

    // ===== 导入识别 =====

    private void startCameraCapture() {
        if (rejectIfBusy()) return;
        try {
            File photoFile = new File(getCacheDir(), "capture_" + System.currentTimeMillis() + ".jpg");
            cameraImageUri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", photoFile);
            cameraLauncher.launch(cameraImageUri);
        } catch (Exception e) {
            StyledToast.show(this, "启动相机失败: " + e.getMessage(), Toast.LENGTH_SHORT);
        }
    }

    /** 从当前页起依次放入导入的图片 (当前页为「＋ 新页」时往后追加)，超出 20 页的忽略 */
    private void importImages(List<Uri> uris) {
        if (rejectIfBusy()) return;
        final int startIdx = currentPage;
        final int capacity = MAX_PAGES - startIdx;
        final List<Uri> accepted = new ArrayList<>(uris.subList(0, Math.min(uris.size(), capacity)));
        final int skipped = uris.size() - accepted.size();
        final int total = accepted.size();
        setBusy(true, total > 1 ? "识别中 1/" + total : "识别中");

        executor.execute(() -> {
            List<WastePage> done = new ArrayList<>();
            int failed = 0;
            PaddleTextRecognizer handwriting;
            try {
                handwriting = getHandwritingRecognizer();
            } catch (Exception e) {
                Log.e(TAG, "加载手写识别模型失败: " + e.getMessage(), e);
                mainHandler.post(() -> {
                    setBusy(false, null);
                    new AlertDialog.Builder(this).setTitle("识别失败").setMessage("加载手写识别模型失败: " + e.getMessage())
                        .setPositiveButton("知道了", null).show();
                });
                return;
            }
            for (int i = 0; i < total; i++) {
                if (i > 0) {
                    final String progress = "识别中 " + (i + 1) + "/" + total;
                    mainHandler.post(() -> tvStatusBadge.setText(progress));
                }
                try {
                    Bitmap bitmap = ImageUtils.loadHighResBitmap(this, accepted.get(i), WasteSheetScanner.MAX_IMAGE_DIM);
                    if (bitmap == null) {
                        failed++;
                        done.add(null);
                        continue;
                    }
                    WastePage page = new WastePage();
                    page.originalBitmap = bitmap;
                    WasteSheetScanner.ScanResult res = WasteSheetScanner.scan(bitmap, categories, handwriting);
                    page.grid = res.grid;
                    page.records = res.records;
                    page.error = res.grid == null ? res.message : null;
                    page.annotatedBitmap = WasteSheetScanner.renderOverlay(bitmap, page.grid, page.records);
                    done.add(page);
                } catch (Exception e) {
                    Log.e(TAG, "处理图片失败: " + e.getMessage(), e);
                    failed++;
                    done.add(null);
                }
            }

            final int failedCount = failed;
            mainHandler.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                int idx = startIdx;
                int rows = 0;
                List<String> unrecognized = new ArrayList<>();
                for (WastePage p : done) {
                    if (p == null) continue;
                    if (idx < pages.size()) {
                        pages.set(idx, p);
                    } else {
                        pages.add(p);
                    }
                    if (p.error != null) unrecognized.add("第" + (idx + 1) + "页");
                    rows += p.records.size();
                    idx++;
                }
                currentPage = startIdx;
                setBusy(false, null);

                StringBuilder msg = new StringBuilder("已识别 " + (done.size() - failedCount) + " 张，共 " + rows + " 行");
                if (unrecognized.isEmpty() && failedCount == 0 && skipped == 0) {
                    StyledToast.show(this, msg, Toast.LENGTH_LONG);
                    return;
                }
                if (!unrecognized.isEmpty()) {
                    msg.append("\n\n").append(String.join("、", unrecognized))
                       .append(" 没找到表头，请把整张表 (含「日期 / 回仓部门…」表头) 拍进画面后重新导入");
                }
                if (failedCount > 0) msg.append("\n\n").append(failedCount).append(" 张图片读取失败，请重试");
                if (skipped > 0) msg.append("\n\n最多 ").append(MAX_PAGES).append(" 页，已忽略最后 ").append(skipped).append(" 张");
                new AlertDialog.Builder(this).setTitle("导入结果").setMessage(msg).setPositiveButton("知道了", null).show();
            });
        });
    }

    // ===== 修改一行 =====

    /** 修改 (rec 为 null 时新增) 一行：类别列可从清单下拉选，填了清单外的新值会记进清单 */
    private void showRowEditor(WastePage page, WasteRecord rec) {
        final boolean isNew = rec == null;
        final WasteRecord target = isNew ? new WasteRecord() : rec;
        if (isNew && !page.records.isEmpty()) {
            // 新行默认沿用最后一行的日期 / 部门 / 地点 / 保安
            WasteRecord lastRec = page.records.get(page.records.size() - 1);
            for (int c : new int[] {WasteRecord.COL_DATE, WasteRecord.COL_DEPT, WasteRecord.COL_LOCATION, WasteRecord.COL_GUARD}) {
                target.set(c, lastRec.get(c), false);
            }
        }

        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(20 * getResources().getDisplayMetrics().density);
        form.setPadding(pad, pad / 4, pad, 0);
        // 照片上这一行的截图，对照着改
        Bitmap rowShot = isNew ? null : WasteSheetScanner.cropRow(page.originalBitmap, page.grid, target.photoRow);
        if (rowShot != null) {
            // 按 64dp 高显示 (整行很长，左右滑动查看)
            int shotH = Math.round(64 * getResources().getDisplayMetrics().density);
            ImageView iv = new ImageView(this);
            iv.setImageBitmap(rowShot);
            iv.setScaleType(ImageView.ScaleType.FIT_XY);
            HorizontalScrollView shotScroll = new HorizontalScrollView(this);
            shotScroll.setBackgroundResource(R.drawable.bg_tag_outline);
            shotScroll.addView(iv, new android.widget.FrameLayout.LayoutParams(
                Math.round((float) shotH * rowShot.getWidth() / rowShot.getHeight()), shotH));
            form.addView(shotScroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, shotH));
        }
        EditText[] inputs = new EditText[WasteRecord.COL_COUNT];
        LayoutInflater inflater = getLayoutInflater();
        for (int c = 0; c < WasteRecord.COL_COUNT; c++) {
            TextInputLayout til = (TextInputLayout) inflater.inflate(R.layout.item_waste_field, form, false);
            MaterialAutoCompleteTextView et = til.findViewById(R.id.et_field);
            til.setHint(WasteRecord.COLUMN_NAMES[c]);
            et.setText(target.get(c));
            if (WasteRecord.isCategoryColumn(c)) {
                et.setSimpleItems(categories.get(c).toArray(new String[0]));
            } else {
                til.setEndIconMode(TextInputLayout.END_ICON_NONE);
                if (c == WasteRecord.COL_WEIGHT) et.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
            }
            if (target.isUncertain(c)) til.setHelperText("没认准，请对照照片核对");
            form.addView(til);
            inputs[c] = et;
        }
        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.addView(form);

        String title = isNew ? "添加一行" : "第 " + (page.records.indexOf(target) + 1) + " 行";
        AlertDialog.Builder builder = new AlertDialog.Builder(this)
            .setTitle(title)
            .setView(scroll)
            .setPositiveButton("保存", (d, w) -> {
                for (int c = 0; c < WasteRecord.COL_COUNT; c++) {
                    String v = inputs[c].getText().toString().trim();
                    // 只记用户亲手改过的值，没改的 (可能是没认准的原文) 不进清单
                    if (!v.equals(target.get(c))) {
                        categories.learnAlias(c, target.getRaw(c), v);
                        categories.learn(c, v);
                    }
                    target.set(c, v, false);
                }
                if (isNew) page.records.add(target);
                redrawPage(page);
            })
            .setNegativeButton("取消", null);
        if (!isNew) {
            builder.setNeutralButton("删除此行", (d, w) -> {
                page.records.remove(target);
                redrawPage(page);
            });
        }
        builder.show();
    }

    private void redrawPage(WastePage page) {
        if (page.originalBitmap != null) {
            page.annotatedBitmap = WasteSheetScanner.renderOverlay(page.originalBitmap, page.grid, page.records);
        }
        refreshUi();
    }

    // ===== 类别清单 =====

    private void showCategoryEditor() {
        final int[] cols = {WasteRecord.COL_DEPT, WasteRecord.COL_ITEM, WasteRecord.COL_LOCATION,
            WasteRecord.COL_FOLLOWER, WasteRecord.COL_GUARD};
        String[] names = new String[cols.length];
        for (int i = 0; i < cols.length; i++) names[i] = WasteRecord.COLUMN_NAMES[cols[i]];
        new AlertDialog.Builder(this)
            .setTitle("编辑哪一列的清单？")
            .setItems(names, (d, which) -> showCategoryListEditor(cols[which]))
            .show();
    }

    private void showCategoryListEditor(int col) {
        EditText et = new EditText(this);
        et.setText(String.join("\n", categories.get(col)));
        et.setMinLines(6);
        et.setGravity(android.view.Gravity.TOP);
        LinearLayout box = new LinearLayout(this);
        int pad = Math.round(20 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, 0, pad, 0);
        box.addView(et, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        new AlertDialog.Builder(this)
            .setTitle(WasteRecord.COLUMN_NAMES[col] + " 清单")
            .setMessage("每行一项。识别时会把手写内容纠正为清单里最像的一项")
            .setView(box)
            .setPositiveButton("保存", (d, w) -> {
                categories.set(col, Arrays.asList(et.getText().toString().split("\n")));
                StyledToast.show(this, "已保存，下次识别生效", Toast.LENGTH_SHORT);
            })
            .setNegativeButton("取消", null)
            .show();
    }

    // ===== 导出 =====

    private List<WasteRecord> getExportRecords() {
        List<WasteRecord> all = new ArrayList<>();
        for (WastePage p : pages) {
            for (WasteRecord r : p.records) {
                if (!r.isEmpty()) all.add(r);
            }
        }
        return all;
    }

    private void showExportSheet() {
        if (rejectIfBusy()) return;
        List<WasteRecord> records = getExportRecords();
        if (records.isEmpty()) {
            StyledToast.show(this, "还没有可导出的行，请先拍照或导入废料登记表", Toast.LENGTH_SHORT);
            return;
        }
        double total = 0;
        int unsure = 0;
        for (WasteRecord r : records) {
            total += Math.max(0, r.getWeight());
            if (r.hasUncertain()) unsure++;
        }

        BottomSheetDialog sheet = new BottomSheetDialog(this);
        View content = getLayoutInflater().inflate(R.layout.bottom_sheet_export, null);
        ((TextView) content.findViewById(R.id.tv_export_summary)).setText(
            pages.size() + " 页共 " + records.size() + " 行，重量合计 " + WasteExcelExporter.formatWeight(total) + " Kg"
            + (unsure > 0 ? "（仍有 " + unsure + " 行带黄底待核对，导出前建议先核对）" : ""));

        LinearLayout options = content.findViewById(R.id.ll_export_options);
        View row = getLayoutInflater().inflate(R.layout.item_export_option, options, false);
        ((ImageView) row.findViewById(R.id.iv_option_icon)).setImageResource(R.drawable.ic_description);
        ((TextView) row.findViewById(R.id.tv_option_title)).setText("导出废料登记表");
        ((TextView) row.findViewById(R.id.tv_option_desc)).setText("与纸质表同列，另附按物品、部门的重量汇总");
        row.setOnClickListener(v -> {
            sheet.dismiss();
            exportExcel(records);
        });
        options.addView(row);
        sheet.setContentView(content);
        sheet.show();
    }

    private void exportExcel(List<WasteRecord> records) {
        setBusy(true, "导出中");
        executor.execute(() -> {
            File file = null;
            String error = null;
            try {
                file = WasteExcelExporter.export(this, records);
            } catch (Exception e) {
                Log.e(TAG, "导出失败: " + e.getMessage(), e);
                error = e.getMessage();
            }
            final File out = file;
            final String err = error;
            mainHandler.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                setBusy(false, null);
                if (out != null) {
                    ExcelExporter.shareFile(this, out, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
                } else {
                    new AlertDialog.Builder(this).setTitle("导出失败").setMessage(err).setPositiveButton("确定", null).show();
                }
            });
        });
    }
}
