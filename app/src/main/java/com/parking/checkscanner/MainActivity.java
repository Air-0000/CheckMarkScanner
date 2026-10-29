package com.parking.checkscanner;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.GestureDetector;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
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
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";

    public static class PageItem {
        public int pageId; // 1 ~ MAX_PAGES
        public String title;
        public String tabName;
        public int startRowIndex;
        public int rowCount;
        public List<ParkingRecord> records;
        public Bitmap originalBitmap;
        public Bitmap annotatedBitmap;
        public CheckmarkDetector.DetectionResult detectionResult;
        public int detectedMarksCount = 0;
        public int ocrCount = 0;
        public boolean fromUser = false; // 已有用户拍照 / 相册导入的图片
        public int ocrStartIdx = -1; // OCR 投票得出的本页起始序号，-1 表示未识别出

        public PageItem(int pageId, int startRowIndex, int rowCount) {
            this.pageId = pageId;
            this.startRowIndex = startRowIndex;
            this.rowCount = rowCount;
            this.records = createDefaultRecords(startRowIndex, rowCount);
            refreshLabels();
        }

        /**
         * 按本页记录的实际序号刷新标题与标签：名单页 (第 1~3 页) 或 OCR 识别出序号的页显示序号范围，
         * 其余页导入后未识别出序号时标注「序号未识别」(这类页导出时不合并)
         */
        void refreshLabels() {
            String page = "第" + pageId + "页";
            if (!records.isEmpty() && (pageId <= ROSTER_PAGES || ocrStartIdx > 0)) {
                int first = Integer.MAX_VALUE;
                int last = Integer.MIN_VALUE;
                for (ParkingRecord r : records) {
                    first = Math.min(first, r.getIndex());
                    last = Math.max(last, r.getIndex());
                }
                title = page + " (序号 " + first + "~" + last + ")";
                tabName = page + " (" + first + "~" + last + ")";
            } else if (originalBitmap != null) {
                title = page + " (序号未识别)";
                tabName = page + " (未识别)";
            } else {
                title = page + " (序号以识别为准)";
                tabName = page;
            }
        }

        private static List<ParkingRecord> createDefaultRecords(int start, int count) {
            List<ParkingRecord> list = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                int idx = start + i;
                list.add(new ParkingRecord(idx, "人员" + idx, "川F" + String.format("%05d", idx)));
            }
            return list;
        }
    }

    // 页面总数，即相册一次最多可导入的图片张数 (从当前页起依次填入后续页面)
    private static final int MAX_PAGES = 20;

    // 第 1~3 页对应名单序号 1~115，导出时始终参与；第 4 页起只有导入过图片且识别出序号的页才合并导出
    private static final int ROSTER_PAGES = 3;

    // 前 3 页对应名单序号 1~115，其余为空白页；启动时不载入任何图片 (页面标签按此数组动态生成，
    // 标题和标签随识别出的序号范围更新)
    private PageItem[] pageItems = buildPageItems();

    private static PageItem[] buildPageItems() {
        PageItem[] items = Arrays.copyOf(new PageItem[] {
            new PageItem(1, 1, 39),
            new PageItem(2, 40, 38),
            new PageItem(3, 78, 38)
        }, MAX_PAGES);
        for (int i = 0; i < MAX_PAGES; i++) {
            if (items[i] != null) continue;
            // 空白页先按 39 行 (序号 1~39) 建网格，导入后以 OCR 识别出的序号为准
            items[i] = new PageItem(i + 1, 1, 39);
        }
        return items;
    }

    // 0 表示还没有导入任何图片 (显示使用步骤)，1~MAX_PAGES 表示当前查看的页
    private int currentViewMode = 0;

    private ExecutorService executor = Executors.newSingleThreadExecutor();
    private Handler mainHandler = new Handler(Looper.getMainLooper());

    private boolean isBusy = false; // 后台识别进行中：禁用导入/导出与单格微调，避免与识别结果互相覆盖
    private Uri cameraImageUri = null;

    // UI 组件
    private MaterialToolbar toolbar;
    private MaterialButton btnCamera, btnGallery, btnExport;
    private ChipGroup chipGroupPages;
    private HorizontalScrollView scrollPageChips;
    private LinearLayout layoutPageStatus, layoutEmptySteps;
    private TextView tvStatusBadge, tvPreviewTip;
    private LinearProgressIndicator progressBusy;

    // 当前页预览与无图占位 (页面标签在 buildPageViews 中按 pageItems 生成，pageChips[i] 对应第 i+1 页)
    private ImageView ivPreview;
    private TextView tvPreviewEmpty;
    private Chip[] pageChips;

    private ActivityResultLauncher<String> galleryLauncher;
    private ActivityResultLauncher<Uri> cameraLauncher;
    private ActivityResultLauncher<String> cameraPermissionLauncher;
    private ActivityResultLauncher<Intent> excelPickerLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        initViews();
        buildPageViews();
        setupLaunchers();
        setupEvents();

        // 启动时还没有图片，显示使用步骤
        switchViewMode(0);

        // 上次停在废料登记表时，启动后直接回到那里 (返回键回到停车打钩)
        if (savedInstanceState == null && WasteActivity.isLastMode(this)) {
            startActivity(new Intent(this, WasteActivity.class));
        }

        // 沿用以前版本自校准过的打钩对比度阈值 (没校准过时为默认值)
        CheckmarkDetector.setCalibratedMinContrast(TrainingDataCollector.getCalibratedMinContrast(this));

        // 启动后静默检测线上新版本
        mainHandler.postDelayed(() -> {
            if (!isFinishing() && !isDestroyed()) {
                AppUpdateManager.getInstance().checkUpdate(MainActivity.this, false);
            }
        }, 1500);
    }

    private void initViews() {
        toolbar = findViewById(R.id.toolbar);
        toolbar.setSubtitle("v" + getVersionName());

        btnCamera = findViewById(R.id.btn_camera);
        btnGallery = findViewById(R.id.btn_gallery);
        btnExport = findViewById(R.id.btn_export);

        chipGroupPages = findViewById(R.id.chip_group_pages);
        scrollPageChips = findViewById(R.id.scroll_page_chips);

        layoutPageStatus = findViewById(R.id.layout_page_status);
        tvStatusBadge = findViewById(R.id.tv_status_badge);
        tvPreviewTip = findViewById(R.id.tv_preview_tip);
        progressBusy = findViewById(R.id.progress_busy);

        ivPreview = findViewById(R.id.iv_preview);
        tvPreviewEmpty = findViewById(R.id.tv_preview_empty);
        layoutEmptySteps = findViewById(R.id.layout_empty_steps);
    }

    /**
     * 按 pageItems 生成顶部页面标签 (每页一个)，各页标签的显隐与文字由 updatePageChips 刷新
     */
    private void buildPageViews() {
        LayoutInflater inflater = getLayoutInflater();
        chipGroupPages.removeAllViews();

        pageChips = new Chip[pageItems.length];
        for (int i = 0; i < pageItems.length; i++) {
            final int targetMode = i + 1;
            Chip chip = (Chip) inflater.inflate(R.layout.chip_page, chipGroupPages, false);
            chip.setId(View.generateViewId());
            chip.setOnClickListener(v -> switchViewMode(targetMode));
            chipGroupPages.addView(chip);
            pageChips[i] = chip;
        }
        updatePageChips();
    }

    /**
     * 页面标签只列出已载入图片的页，另留一个「＋ 新页」指向第一张空白页，供逐张拍照时往后加页
     * (还没有任何图片时整行页签由 updatePageStatus 隐藏)
     */
    private void updatePageChips() {
        int firstEmpty = -1;
        for (int i = 0; i < pageItems.length; i++) {
            if (pageItems[i].originalBitmap == null) {
                firstEmpty = i;
                break;
            }
        }
        for (int i = 0; i < pageItems.length; i++) {
            Chip chip = pageChips[i];
            if (pageItems[i].originalBitmap != null) {
                chip.setText(pageItems[i].tabName);
                chip.setVisibility(View.VISIBLE);
            } else if (i == firstEmpty) {
                chip.setText("＋ 新页");
                chip.setVisibility(View.VISIBLE);
            } else {
                chip.setVisibility(View.GONE);
            }
        }
    }

    private void setupLaunchers() {
        // 相册一次多选：从当前聚焦页开始依次填入
        galleryLauncher = registerForActivityResult(
            new ActivityResultContracts.GetMultipleContents(),
            uris -> {
                if (uris != null && !uris.isEmpty()) {
                    importImages(uris);
                }
            }
        );

        cameraLauncher = registerForActivityResult(
            new ActivityResultContracts.TakePicture(),
            success -> {
                if (success && cameraImageUri != null) {
                    List<Uri> single = new ArrayList<>();
                    single.add(cameraImageUri);
                    importImages(single);
                }
            }
        );

        cameraPermissionLauncher = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(),
            isGranted -> {
                if (isGranted) {
                    startCameraCapture();
                } else {
                    StyledToast.show(this, "需要相机权限拍摄纸张表格", Toast.LENGTH_SHORT);
                }
            }
        );

        excelPickerLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    Uri uri = result.getData().getData();
                    if (uri != null) {
                        processExcelFilling(uri);
                    }
                }
            }
        );
    }

    private void setupEvents() {
        toolbar.setOnMenuItemClickListener(this::onToolbarMenuItemClick);

        btnCamera.setOnClickListener(v -> {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                startCameraCapture();
            } else {
                cameraPermissionLauncher.launch(Manifest.permission.CAMERA);
            }
        });

        btnGallery.setOnClickListener(v -> galleryLauncher.launch("image/*"));
        btnExport.setOnClickListener(v -> showExportSheet());

        // 单图预览手势监听：精准单格点击补勾/取消
        GestureDetector gestureDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                if (currentViewMode >= 1 && currentViewMode <= pageItems.length) {
                    handleImageTouch(e.getX(), e.getY());
                    return true;
                }
                return false;
            }
        });

        ivPreview.setOnTouchListener((v, event) -> {
            gestureDetector.onTouchEvent(event);
            return false;
        });
    }

    private boolean onToolbarMenuItemClick(MenuItem menuItem) {
        int id = menuItem.getItemId();
        if (id == R.id.action_waste_mode) {
            startActivity(new Intent(this, WasteActivity.class));
        } else if (id == R.id.action_check_update) {
            AppUpdateManager.getInstance().checkUpdate(this, true);
        } else if (id == R.id.action_update_url) {
            AppUpdateManager.getInstance().showConfigureUrlDialog(this);
        } else {
            return false;
        }
        return true;
    }

    private String getVersionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 进入/退出后台识别 (或导出) 状态：显示进度条并锁定会改动识别结果的入口；
     * busyText 显示在状态标签上，退出时状态行恢复为当前页信息
     */
    private void setBusy(boolean busy, String busyText) {
        isBusy = busy;
        progressBusy.setVisibility(busy ? View.VISIBLE : View.INVISIBLE);
        btnCamera.setEnabled(!busy);
        btnGallery.setEnabled(!busy);
        btnExport.setEnabled(!busy);
        if (busy) {
            tvStatusBadge.setText(busyText);
            tvPreviewTip.setText("请稍候...");
            applyStatusBadgeStyle(true);
        }
        updatePageStatus();
    }

    /**
     * 状态标签：识别中或需要注意 (序号未识别) 时为黄底警示样式，其余为描边样式
     */
    private void applyStatusBadgeStyle(boolean alert) {
        tvStatusBadge.setBackgroundResource(alert ? R.drawable.bg_tag_hazard : R.drawable.bg_tag_outline);
        tvStatusBadge.setTextColor(ContextCompat.getColor(this, alert ? R.color.on_hazard : R.color.text_main));
    }

    private boolean rejectIfBusy() {
        if (isBusy) {
            StyledToast.show(this, "正在识别中，请稍候...", Toast.LENGTH_SHORT);
        }
        return isBusy;
    }

    /**
     * 切换到第 mode 页 (mode 为 0 表示还没有图片，显示使用步骤)
     */
    private void switchViewMode(int mode) {
        currentViewMode = mode;
        PageItem item = (mode >= 1 && mode <= pageItems.length) ? pageItems[mode - 1] : null;
        if (item != null) updatePageNavUI(mode);

        boolean hasImage = item != null && item.originalBitmap != null;
        ivPreview.setVisibility(hasImage ? View.VISIBLE : View.GONE);
        tvPreviewEmpty.setVisibility(item != null && !hasImage ? View.VISIBLE : View.GONE);
        layoutEmptySteps.setVisibility(item == null ? View.VISIBLE : View.GONE);
        if (hasImage) {
            ivPreview.setImageBitmap(item.annotatedBitmap != null ? item.annotatedBitmap : item.originalBitmap);
        }

        updatePageStatus();
    }

    private void updatePageNavUI(int mode) {
        // ChipGroup 为单选，选中目标标签会自动取消其余标签
        Chip selected = pageChips[mode - 1];
        selected.setChecked(true);
        // 代码切换页面时 (如导入后跳到新导入的页)，把选中的标签滚动到可见位置
        selected.post(() -> scrollPageChips.smoothScrollTo(
            Math.max(0, selected.getLeft() - scrollPageChips.getPaddingStart()), 0));
    }

    /**
     * 刷新页签与状态行：标签显示当前页打钩数，第 4 页起没认出序号的页用警示样式提醒重拍；
     * 还没有图片时隐藏整行页签和状态 (识别中仍显示状态)
     */
    private void updatePageStatus() {
        boolean anyLoaded = countLoadedPages() > 0;
        scrollPageChips.setVisibility(anyLoaded ? View.VISIBLE : View.GONE);
        layoutPageStatus.setVisibility(anyLoaded || isBusy ? View.VISIBLE : View.GONE);
        if (isBusy || currentViewMode < 1 || currentViewMode > pageItems.length) return;

        PageItem item = pageItems[currentViewMode - 1];
        boolean alert = false;
        if (item.originalBitmap == null) {
            tvStatusBadge.setText("新页");
            tvPreviewTip.setText("拍照或从相册导入，放到这一页");
        } else if (!isExportPage(currentViewMode - 1)) {
            alert = true;
            tvStatusBadge.setText("序号未识别");
            tvPreviewTip.setText("这页不会导出：请拍清左侧序号列后，在这一页重新拍照或导入");
        } else {
            int marks = 0;
            for (ParkingRecord r : item.records) marks += r.getCheckedCount();
            tvStatusBadge.setText(marks + " 处打钩");
            tvPreviewTip.setText("对照照片核对黄框，点格子补勾 / 取消");
        }
        applyStatusBadgeStyle(alert);
    }

    /**
     * 对单页图片执行：打钩检测 -> 端侧 OCR 花名册提取 -> 生成仅框叠加图 (需在后台线程调用)
     */
    private void analyzePage(PageItem item, Bitmap bitmap) {
        item.originalBitmap = bitmap;
        item.annotatedBitmap = null;
        item.ocrStartIdx = -1;

        // 1. 物理表格线与打钩检测 (基于拓扑和污渍过滤算法)
        CheckmarkDetector.DetectionResult res = CheckmarkDetector.detect(
            bitmap, item.records, 1.0f, 0, 0, false, false
        );
        item.detectionResult = res;
        if (res == null) {
            item.refreshLabels();
            return;
        }

        item.records = res.records;
        item.detectedMarksCount = res.totalMarkCount;

        // 2. 端侧离线 OCR 动态提取纸质表格花名册
        try {
            TableOcrScanner.OcrResult ocrRes = TableOcrScanner.scanTable(bitmap, res.grid, res.records);
            if (ocrRes != null && ocrRes.success && ocrRes.records != null && !ocrRes.records.isEmpty()) {
                for (int r = 0; r < Math.min(res.records.size(), ocrRes.records.size()); r++) {
                    ParkingRecord original = res.records.get(r);
                    ParkingRecord ocrRec = ocrRes.records.get(r);
                    ocrRec.copyShiftsFrom(original);
                }
                res.records = ocrRes.records;
                item.records = ocrRes.records;
                item.ocrCount = ocrRes.recognizedItemCount;
                item.ocrStartIdx = ocrRes.detectedStartIdx;
            }
        } catch (Exception e) {
            Log.e(TAG, "OCR 处理异常: " + e.getMessage(), e);
        }

        // 3. 生成展示图片 (只画识别框，不画单元格坐标与辅助网格)
        item.annotatedBitmap = CheckmarkDetector.renderOverlay(
            bitmap, item.records, res.grid, false, false, 0, 0
        );

        // 4. 标题 / 标签改为识别出的序号范围
        item.refreshLabels();
    }

    private void handleImageTouch(float touchX, float touchY) {
        if (isBusy) return;
        if (currentViewMode < 1 || currentViewMode > pageItems.length) return;
        PageItem item = pageItems[currentViewMode - 1];
        if (item.originalBitmap == null || ivPreview.getDrawable() == null) return;

        Drawable drawable = ivPreview.getDrawable();
        int dW = drawable.getIntrinsicWidth();
        int dH = drawable.getIntrinsicHeight();
        if (dW <= 0 || dH <= 0) return;

        int vW = ivPreview.getWidth();
        int vH = ivPreview.getHeight();
        if (vW <= 0 || vH <= 0) return;

        Matrix matrix = ivPreview.getImageMatrix();
        float[] values = new float[9];
        matrix.getValues(values);
        float scaleX = values[Matrix.MSCALE_X];
        float scaleY = values[Matrix.MSCALE_Y];
        float transX = values[Matrix.MTRANS_X];
        float transY = values[Matrix.MTRANS_Y];

        float bmX;
        float bmY;
        if (scaleX > 0 && scaleY > 0) {
            bmX = (touchX - transX) / scaleX;
            bmY = (touchY - transY) / scaleY;
        } else {
            float s = Math.min((float) vW / dW, (float) vH / dH);
            float padX = (vW - dW * s) / 2.0f;
            float padY = (vH - dH * s) / 2.0f;
            bmX = (touchX - padX) / s;
            bmY = (touchY - padY) / s;
        }

        int imgW = item.originalBitmap.getWidth();
        int imgH = item.originalBitmap.getHeight();
        CheckmarkDetector.TableGrid grid = (item.detectionResult != null) ? item.detectionResult.grid : null;

        int[] hit = CheckmarkDetector.getCellAtPoint(bmX, bmY, imgW, imgH, grid, 0, 0, item.records.size());
        if (hit != null) {
            int row = hit[0];
            int col = hit[1];
            if (row >= 0 && row < item.records.size()) {
                ParkingRecord rec = item.records.get(row);
                rec.toggleShift(col);
                boolean nowChecked = rec.isShiftChecked(col);

                // 重新计数
                int newCount = 0;
                for (ParkingRecord r : item.records) newCount += r.getCheckedCount();
                item.detectedMarksCount = newCount;

                // 只重画当前页的识别框
                item.annotatedBitmap = CheckmarkDetector.renderOverlay(
                    item.originalBitmap, item.records, grid, false, false, 0, 0
                );
                ivPreview.setImageBitmap(item.annotatedBitmap);
                updatePageStatus();

                StyledToast.show(this, "序号" + rec.getIndex() + " " + rec.getName() + " · "
                    + ShiftConstants.SHIFT_NAMES[col] + (nowChecked ? "：已补勾" : "：已取消"), Toast.LENGTH_SHORT);
            }
        }
    }

    /**
     * 导入一张或多张图片：从当前页开始 (还没有图片时从第 1 页开始)，按选择顺序依次填入后续页面。
     * 超出剩余页数的图片会被忽略并提示。
     */
    private void importImages(List<Uri> uris) {
        if (uris == null || uris.isEmpty() || rejectIfBusy()) return;

        final int startIdx = (currentViewMode >= 1 && currentViewMode <= pageItems.length) ? (currentViewMode - 1) : 0;
        final int capacity = pageItems.length - startIdx;
        final List<Uri> accepted = new ArrayList<>(uris.subList(0, Math.min(uris.size(), capacity)));
        final int skipped = uris.size() - accepted.size();
        final int total = accepted.size();

        setBusy(true, total > 1 ? "识别中 1/" + total : "识别中");

        executor.execute(() -> {
            List<String> doneNames = new ArrayList<>();
            List<String> unmergedNames = new ArrayList<>();
            int failed = 0;
            int firstDone = -1;

            for (int i = 0; i < total; i++) {
                PageItem target = pageItems[startIdx + i];
                if (i > 0) {
                    final String progress = "识别中 " + (i + 1) + "/" + total;
                    mainHandler.post(() -> tvStatusBadge.setText(progress));
                }
                try {
                    Bitmap bitmap = ImageUtils.loadAndNormalizeBitmap(this, accepted.get(i));
                    if (bitmap == null) {
                        failed++;
                        continue;
                    }
                    target.fromUser = true;
                    analyzePage(target, bitmap);
                    if (firstDone < 0) firstDone = startIdx + i;
                    // 提示里只用短名 (去掉括号里的序号范围)，避免 Toast 超过两行被截断
                    String shortName = target.tabName.replaceAll("\\s*\\(.*\\)", "");
                    doneNames.add(shortName + " " + target.detectedMarksCount + "处");
                    if (!isExportPage(startIdx + i)) unmergedNames.add(shortName);
                } catch (Exception e) {
                    failed++;
                    Log.e(TAG, "处理图片失败: " + e.getMessage(), e);
                }
            }

            final int failedCount = failed;
            final int firstDonePage = firstDone;
            mainHandler.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                updatePageChips();
                // 聚焦到导入的第一页便于核对微调，其余页从上方页签切换
                switchViewMode(firstDonePage >= 0 ? firstDonePage + 1 : currentViewMode);

                StringBuilder msg = new StringBuilder();
                if (!doneNames.isEmpty()) {
                    msg.append("已识别 ").append(doneNames.size()).append(" 张：").append(String.join("、", doneNames));
                }
                setBusy(false, null);

                if (failedCount == 0 && skipped == 0 && unmergedNames.isEmpty()) {
                    StyledToast.show(MainActivity.this, msg.toString(), Toast.LENGTH_LONG);
                    return;
                }
                // 有读取失败、超出页数被忽略或无法合并导出的图片时用弹窗完整说明，避免 Toast 截断漏看
                if (!unmergedNames.isEmpty()) {
                    if (msg.length() > 0) msg.append("\n\n");
                    msg.append(String.join("、", unmergedNames))
                       .append(" 未识别出序号，导出时不会合并。请拍清左侧序号列后重新导入。");
                }
                if (failedCount > 0) {
                    if (msg.length() > 0) msg.append("\n\n");
                    msg.append(failedCount).append(" 张图片读取失败，请重试");
                }
                if (skipped > 0) {
                    if (msg.length() > 0) msg.append("\n\n");
                    msg.append("从当前页起最多可放 ").append(capacity).append(" 张，已忽略最后 ").append(skipped)
                       .append(" 张。如需导入更多，请先切到靠前的页面再选择。");
                }
                new AlertDialog.Builder(MainActivity.this)
                    .setTitle("导入结果")
                    .setMessage(msg.toString())
                    .setPositiveButton("知道了", null)
                    .show();
            });
        });
    }

    private void startCameraCapture() {
        if (rejectIfBusy()) return;
        try {
            File photoFile = new File(getCacheDir(), "capture_" + System.currentTimeMillis() + ".jpg");
            cameraImageUri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", photoFile);
            cameraLauncher.launch(cameraImageUri);
        } catch (Exception e) {
            e.printStackTrace();
            StyledToast.show(this, "启动相机失败: " + e.getMessage(), Toast.LENGTH_SHORT);
        }
    }

    /**
     * 第 1~3 页始终参与导出 (与原先一致)；第 4 页起只合并用户导入且 OCR 识别出序号的页面，
     * 避免序号未知的照片按默认序号 1~39 错填到别人名下。
     */
    private boolean isExportPage(int pageIdx) {
        PageItem item = pageItems[pageIdx];
        return pageIdx < ROSTER_PAGES || (item.fromUser && item.ocrStartIdx > 0);
    }

    /**
     * 导出用名单：把参与导出的页面按序号合并为一份 (同一人出现在多张图上时打钩取并集，姓名车牌取靠前页面)。
     * 返回的是副本，修改不会影响各页记录。
     */
    private List<ParkingRecord> getExportRecords() {
        Map<Integer, ParkingRecord> merged = new TreeMap<>();
        for (int i = 0; i < pageItems.length; i++) {
            if (!isExportPage(i)) continue;
            for (ParkingRecord r : pageItems[i].records) {
                ParkingRecord m = merged.get(r.getIndex());
                if (m == null) {
                    m = new ParkingRecord(r.getIndex(), r.getName(), r.getPlateNumber());
                    merged.put(r.getIndex(), m);
                }
                for (int c = 0; c < 14; c++) {
                    if (r.isShiftChecked(c)) {
                        m.setShiftChecked(c, true);
                        m.setConfidence(c, Math.max(m.getConfidence(c), r.getConfidence(c)));
                    }
                }
            }
        }
        return new ArrayList<>(merged.values());
    }

    private int countLoadedPages() {
        int loadedPages = 0;
        for (PageItem item : pageItems) {
            if (item.originalBitmap != null) loadedPages++;
        }
        return loadedPages;
    }

    /**
     * 导出入口：底部弹层先汇总合并结果，再选导出方式 (停车情况表副本填1 / 导入表格填1)
     */
    private void showExportSheet() {
        List<ParkingRecord> allRecords = getExportRecords();
        int attended = 0;
        int totalMarks = 0;
        for (ParkingRecord r : allRecords) {
            if (r.hasAnyChecked()) attended++;
            totalMarks += r.getCheckedCount();
        }
        int mergedPages = 0;
        int unmergedPages = 0;
        for (int i = 0; i < pageItems.length; i++) {
            if (isExportPage(i)) {
                mergedPages++;
            } else if (pageItems[i].fromUser) {
                unmergedPages++;
            }
        }

        BottomSheetDialog sheet = new BottomSheetDialog(this);
        View content = getLayoutInflater().inflate(R.layout.bottom_sheet_export, null);
        ((TextView) content.findViewById(R.id.tv_export_summary)).setText(
            mergedPages + " 页按序号合并后共 " + totalMarks + " 处打钩、" + attended + " 人出勤"
            + (unmergedPages > 0 ? "（另有 " + unmergedPages + " 页未识别出序号，不参与导出）" : "")
            + "，导出时打钩统一填为数字 1");

        LinearLayout options = content.findViewById(R.id.ll_export_options);
        addExportOption(sheet, options, R.drawable.ic_description, "填入停车情况表（推荐）",
            "在内置《停车情况》模板的副本上填 1 后导出，原表格式不变", () -> processExcelFilling(null));
        addExportOption(sheet, options, R.drawable.ic_upload_file, "导入我的表格填 1",
            "选择手机里的 .xlsx（如更新过名单的停车情况表），在副本上填 1", this::pickUserExcel);

        sheet.setContentView(content);
        sheet.show();
    }

    private void addExportOption(BottomSheetDialog sheet, LinearLayout container, int iconRes,
                                 String title, String desc, Runnable action) {
        View row = getLayoutInflater().inflate(R.layout.item_export_option, container, false);
        ((ImageView) row.findViewById(R.id.iv_option_icon)).setImageResource(iconRes);
        ((TextView) row.findViewById(R.id.tv_option_title)).setText(title);
        ((TextView) row.findViewById(R.id.tv_option_desc)).setText(desc);
        row.setOnClickListener(v -> {
            sheet.dismiss();
            action.run();
        });
        container.addView(row);
    }

    private void pickUserExcel() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[] {
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/vnd.ms-excel",
            "application/octet-stream"
        });
        excelPickerLauncher.launch(intent);
    }

    private void processExcelFilling(Uri userExcelUri) {
        setBusy(true, "导出中");

        // 在主线程取好合并后的副本再交给后台，避免与界面上的补勾 / 取消同时读写各页记录
        final List<ParkingRecord> allRecords = getExportRecords();
        executor.execute(() -> {
            ExcelTemplateFiller.FillResult result;
            if (userExcelUri == null) {
                result = ExcelTemplateFiller.fillBuiltInTemplate(this, allRecords);
            } else {
                result = ExcelTemplateFiller.fillUserExcel(this, userExcelUri, allRecords);
            }

            mainHandler.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                setBusy(false, null);
                if (result.success && result.outputFile != null) {
                    StyledToast.show(MainActivity.this, "已按输入表格成功填入 " + result.filledMarksCount + " 处 1，正在调起导出！", Toast.LENGTH_LONG);
                    // 和原来导出表格完全一样，直接调起系统分享 / 微信 / WPS
                    ExcelExporter.shareFile(MainActivity.this, result.outputFile, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
                } else {
                    AlertDialog.Builder errBuilder = new AlertDialog.Builder(MainActivity.this);
                    errBuilder.setTitle("导出失败");
                    errBuilder.setMessage(result.message != null ? result.message : "处理 Excel 失败，请确认文件格式为有效 .xlsx");
                    errBuilder.setPositiveButton("确定", null);
                    errBuilder.show();
                }
            });
        });
    }
}
