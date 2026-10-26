package com.parking.checkscanner;

import android.Manifest;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
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
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import java.io.File;
import java.io.InputStream;
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
        public String assetFileName; // 示例图文件名，空白页为 null
        public int startRowIndex;
        public int rowCount;
        public List<ParkingRecord> records;
        public Bitmap originalBitmap;
        public Bitmap annotatedBitmap;
        public CheckmarkDetector.DetectionResult detectionResult;
        public int detectedMarksCount = 0;
        public int ocrCount = 0;
        public boolean fromUser = false; // 当前图片为用户拍照 / 相册导入 (示例图为 false)
        public int ocrStartIdx = -1; // OCR 投票得出的本页起始序号，-1 表示未识别出

        public PageItem(int pageId, String title, String tabName, String assetFileName, int startRowIndex, int rowCount) {
            this.pageId = pageId;
            this.title = title;
            this.tabName = tabName;
            this.assetFileName = assetFileName;
            this.startRowIndex = startRowIndex;
            this.rowCount = rowCount;
            this.records = createDefaultRecords(startRowIndex, rowCount);
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

    // 前 5 页为实拍与示例图，其余为空白页 (页面标签与纵览卡片均按此数组动态生成)
    private PageItem[] pageItems = buildPageItems();

    private static PageItem[] buildPageItems() {
        PageItem[] items = Arrays.copyOf(new PageItem[] {
            new PageItem(1, "实拍图 1 · 第1页 (序号 1~39)", "实拍P1 (1~39)", "sample_p1.jpg", 1, 39),
            new PageItem(2, "实拍图 2 · 第2页 (序号 40~77)", "实拍P2 (40~77)", "sample_p2.jpg", 40, 38),
            new PageItem(3, "实拍图 3 · 第3页 (序号 78~115)", "实拍P3 (78~115)", "sample_p3.jpg", 78, 38),
            new PageItem(4, "实拍图 4 · 备选第1页 (序号 1~39)", "实拍P4 (备选)", "sample_p4.jpg", 1, 39),
            new PageItem(5, "原始示例表 (序号 1~39)", "原始示例表", "sample_parking_table.jpg", 1, 39)
        }, MAX_PAGES);
        for (int i = 0; i < MAX_PAGES; i++) {
            if (items[i] != null) continue;
            int pageId = i + 1;
            // 空白页先按 39 行 (序号 1~39) 建网格，导入后以 OCR 识别出的序号为准
            items[i] = new PageItem(pageId, "第" + pageId + "页 (序号以识别为准)", "第" + pageId + "页", null, 1, 39);
        }
        return items;
    }

    // 0 表示全部图片纵览模式，1~MAX_PAGES 表示聚焦对应单图
    private int currentViewMode = 0;

    private RecordAdapter adapter;
    private ExecutorService executor = Executors.newSingleThreadExecutor();
    private Handler mainHandler = new Handler(Looper.getMainLooper());

    private float currentSensitivity = 1.0f;
    private int sensitivityMode = 0; // 0=自适应 (1.0x), 1=增强 (1.25x), 2=严格 (0.85x)
    private int currentOffsetX = 0;
    private int currentOffsetY = 0;
    private boolean showHelperGrid = false;
    private boolean showBadge = false; // 用户要求：默认仅展示框体，避免标号遮挡原图打钩细节
    private boolean isBusy = false; // 后台识别进行中：禁用导入/导出与单格微调，避免与识别结果互相覆盖
    private Uri cameraImageUri = null;
    private String summaryText = "";

    // UI 组件
    private MaterialToolbar toolbar;
    private MaterialButton btnCamera, btnGallery, btnExport;
    private MaterialButtonToggleGroup toggleView;
    private Chip chipSensitivity, chipBadge, chipGrid;
    private ChipGroup chipGroupPages;
    private HorizontalScrollView scrollPageChips;
    private LinearLayout layoutImageView, layoutRecordsView;
    private TextView tvStatMarks, tvStatPersons, tvStatPages, tvStatusBadge, tvPreviewTip;
    private LinearProgressIndicator progressBusy;
    private EditText etSearch;
    private RecyclerView rvRecords;

    // 单图预览与多卡片纵览 (标签与卡片在 buildPageViews 中按 pageItems 生成)
    private ImageView ivPreview;
    private LinearLayout llAllImagesCards;
    private Chip[] pageChips;
    private ImageView[] cardImages;
    private TextView[] cardPlaceholders;
    private TextView[] cardMarks;

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

        // 初始化花名册适配器 (初始载入全量人员)
        adapter = new RecordAdapter(getCombinedRecords(1, 2, 3), new RecordAdapter.OnItemClickListener() {
            @Override
            public void onItemClick(ParkingRecord record, int position) {
                showEditRecordDialog(record, position);
            }

            @Override
            public void onShiftToggled(ParkingRecord record, int shiftIndex) {
                updateStatsDisplay();
                updateSummaryText();
                refreshAllImageOverlays();
            }
        });

        rvRecords.setLayoutManager(new LinearLayoutManager(this));
        rvRecords.setAdapter(adapter);

        // 默认显示效果预览视图 (全部图片纵览模式，仅框)
        switchTab(0);
        switchViewMode(0);

        // 载入沉淀的自校准算法阈值与训练集样本数徽标
        CheckmarkDetector.setCalibratedMinContrast(TrainingDataCollector.getCalibratedMinContrast(this));
        updateTrainingDataButtonBadge();

        // 启动时自动载入全部 5 张实拍与示例图
        loadAllSampleImages();

        // 启动后静默检测线上新版本
        mainHandler.postDelayed(() -> {
            if (!isFinishing() && !isDestroyed()) {
                AppUpdateManager.getInstance().checkUpdate(MainActivity.this, false);
            }
        }, 1500);
    }

    private void initViews() {
        toolbar = findViewById(R.id.toolbar);
        toolbar.setSubtitle("v" + getVersionName() + " · 端侧离线识别 · Excel 智能填报");

        btnCamera = findViewById(R.id.btn_camera);
        btnGallery = findViewById(R.id.btn_gallery);
        btnExport = findViewById(R.id.btn_export);

        toggleView = findViewById(R.id.toggle_view);
        chipGroupPages = findViewById(R.id.chip_group_pages);
        scrollPageChips = findViewById(R.id.scroll_page_chips);
        chipSensitivity = findViewById(R.id.chip_sensitivity);
        chipBadge = findViewById(R.id.chip_badge);
        chipGrid = findViewById(R.id.chip_grid);

        layoutImageView = findViewById(R.id.layout_image_view);
        layoutRecordsView = findViewById(R.id.layout_records_view);

        tvStatMarks = findViewById(R.id.tv_stat_marks);
        tvStatPersons = findViewById(R.id.tv_stat_persons);
        tvStatPages = findViewById(R.id.tv_stat_pages);
        tvStatusBadge = findViewById(R.id.tv_status_badge);
        tvPreviewTip = findViewById(R.id.tv_preview_tip);
        progressBusy = findViewById(R.id.progress_busy);

        etSearch = findViewById(R.id.et_search);
        rvRecords = findViewById(R.id.rv_records);

        ivPreview = findViewById(R.id.iv_preview);
        llAllImagesCards = findViewById(R.id.ll_all_images_cards);
    }

    /**
     * 按 pageItems 生成顶部页面标签 (全部 + 每页) 与纵览模式下的图片卡片
     */
    private void buildPageViews() {
        LayoutInflater inflater = getLayoutInflater();
        chipGroupPages.removeAllViews();
        llAllImagesCards.removeAllViews();

        pageChips = new Chip[pageItems.length + 1];
        cardImages = new ImageView[pageItems.length];
        cardPlaceholders = new TextView[pageItems.length];
        cardMarks = new TextView[pageItems.length];

        for (int mode = 0; mode <= pageItems.length; mode++) {
            final int targetMode = mode;
            Chip chip = (Chip) inflater.inflate(R.layout.chip_page, chipGroupPages, false);
            chip.setId(View.generateViewId());
            // 已载入张数见统计栏，这里不写总页数，免得误以为已有 20 张图
            chip.setText(mode == 0 ? "全部" : pageItems[mode - 1].tabName);
            chip.setOnClickListener(v -> switchViewMode(targetMode));
            chipGroupPages.addView(chip);
            pageChips[mode] = chip;
        }

        for (int i = 0; i < pageItems.length; i++) {
            final int targetMode = i + 1;
            View card = inflater.inflate(R.layout.item_page_card, llAllImagesCards, false);
            ((TextView) card.findViewById(R.id.tv_card_title)).setText(pageItems[i].title);
            cardImages[i] = card.findViewById(R.id.iv_card);
            cardPlaceholders[i] = card.findViewById(R.id.tv_card_placeholder);
            cardMarks[i] = card.findViewById(R.id.tv_card_marks);
            // 点击全图列表中的卡片，可直接快捷进入该图的单图聚焦微调视图
            card.setOnClickListener(v -> switchViewMode(targetMode));
            llAllImagesCards.addView(card);
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
                    Toast.makeText(this, "需要相机权限拍摄纸张表格", Toast.LENGTH_SHORT).show();
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

        chipSensitivity.setOnClickListener(v -> cycleSensitivity());

        // 标号显隐切换 (只留框体 vs 标号+框体)
        chipBadge.setOnCheckedChangeListener((v, checked) -> {
            showBadge = checked;
            refreshAllImageOverlays();
        });

        // 辅助网格切换
        chipGrid.setOnCheckedChangeListener((v, checked) -> {
            showHelperGrid = checked;
            refreshAllImageOverlays();
        });

        // 视图切换 (效果预览 / 核对名单)
        toggleView.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (isChecked) {
                switchTab(checkedId == R.id.tab_records ? 1 : 0);
            }
        });

        // 搜索框过滤
        etSearch.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                adapter.filter(s.toString());
            }
            @Override
            public void afterTextChanged(Editable s) {}
        });

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
        if (id == R.id.action_training) {
            showTrainingDataDialog();
        } else if (id == R.id.action_reload) {
            if (!rejectIfBusy()) loadAllSampleImages();
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
     * 进入/退出后台识别状态：显示进度条并锁定会改动识别结果的入口
     */
    private void setBusy(boolean busy, String statusText) {
        isBusy = busy;
        progressBusy.setVisibility(busy ? View.VISIBLE : View.INVISIBLE);
        btnCamera.setEnabled(!busy);
        btnGallery.setEnabled(!busy);
        btnExport.setEnabled(!busy);
        chipSensitivity.setEnabled(!busy);
        if (statusText != null) {
            tvStatusBadge.setText(statusText);
        }
        tvStatusBadge.setTextColor(ContextCompat.getColor(this, busy ? R.color.primary : R.color.checked_green));
    }

    private boolean rejectIfBusy() {
        if (isBusy) {
            Toast.makeText(this, "正在识别中，请稍候...", Toast.LENGTH_SHORT).show();
        }
        return isBusy;
    }

    private void switchViewMode(int mode) {
        currentViewMode = mode;
        updatePageNavUI(mode);

        if (mode == 0) {
            // 全部图片纵览模式
            llAllImagesCards.setVisibility(View.VISIBLE);
            ivPreview.setVisibility(View.GONE);
            adapter.setRecords(getCombinedRecords(1, 2, 3));
            tvPreviewTip.setText("点击任意图片进入单页，可逐格补勾 / 取消");
        } else {
            // 单图聚焦模式
            llAllImagesCards.setVisibility(View.GONE);
            ivPreview.setVisibility(View.VISIBLE);

            PageItem item = pageItems[mode - 1];
            if (item.annotatedBitmap != null) {
                ivPreview.setImageBitmap(item.annotatedBitmap);
            } else {
                ivPreview.setImageBitmap(item.originalBitmap);
            }
            adapter.setRecords(item.records);
            tvPreviewTip.setText(item.originalBitmap != null
                ? "点击表格中的格子即可补勾 / 取消，修正会自动累计为训练样本"
                : "本页暂无图片：拍照或从相册导入后即可识别");
        }
        tvStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.checked_green));

        updateStatsDisplay();
        updateSummaryText();
    }

    private void updatePageNavUI(int mode) {
        // ChipGroup 为单选，选中目标标签会自动取消其余标签
        Chip selected = pageChips[mode];
        selected.setChecked(true);
        // 代码切换页面时 (如多选导入后回到纵览)，把选中的标签滚动到可见位置
        selected.post(() -> scrollPageChips.smoothScrollTo(
            Math.max(0, selected.getLeft() - scrollPageChips.getPaddingStart()), 0));
    }

    private void switchTab(int tabIndex) {
        layoutImageView.setVisibility(tabIndex == 0 ? View.VISIBLE : View.GONE);
        layoutRecordsView.setVisibility(tabIndex == 1 ? View.VISIBLE : View.GONE);
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
            bitmap, item.records, currentSensitivity, 0, 0, showHelperGrid, showBadge
        );
        item.detectionResult = res;
        if (res == null) return;

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

        // 3. 生成展示图片 (遵循 showBadge=false，仅框展示)
        item.annotatedBitmap = CheckmarkDetector.renderOverlay(
            bitmap, item.records, res.grid, showHelperGrid, showBadge, 0, 0
        );
    }

    private void loadAllSampleImages() {
        setBusy(true, "正在分析全部实拍与示例图...");

        executor.execute(() -> {
            int sampleCount = 0;
            for (PageItem item : pageItems) {
                if (item.assetFileName == null) continue;
                sampleCount++;
                try (InputStream is = getAssets().open(item.assetFileName)) {
                    Bitmap bitmap = BitmapFactory.decodeStream(is);
                    item.fromUser = false;
                    analyzePage(item, bitmap);
                } catch (Exception e) {
                    Log.e(TAG, "加载 " + item.assetFileName + " 失败: " + e.getMessage(), e);
                }
            }

            final int loadedSamples = sampleCount;
            mainHandler.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                updateAllCardsUI();
                switchViewMode(currentViewMode);
                setBusy(false, loadedSamples + " 张示例图已就绪 (仅框)");
                Toast.makeText(MainActivity.this, "已载入全部 " + loadedSamples + " 张实拍与示例图，每张图仅展示打钩框体！", Toast.LENGTH_SHORT).show();
            });
        });
    }

    /**
     * 智能灵敏度自适应切换：
     * 0: 智能自适应 (1.0x, 推荐标准)
     * 1: 增强模式 (1.25x, 抓淡笔、浅铅笔、细字迹)
     * 2: 严格模式 (0.85x, 抗重污、防透印反光)
     */
    private void cycleSensitivity() {
        if (rejectIfBusy()) return;
        sensitivityMode = (sensitivityMode + 1) % 3;
        String tip;
        if (sensitivityMode == 1) {
            currentSensitivity = 1.25f;
            chipSensitivity.setText("灵敏度：增强");
            tip = "已切换为【增强模式】：更灵敏捕获浅铅笔/淡笔迹打钩";
        } else if (sensitivityMode == 2) {
            currentSensitivity = 0.85f;
            chipSensitivity.setText("灵敏度：严格");
            tip = "已切换为【严格模式】：抗纸张重度反光与透印杂斑";
        } else {
            currentSensitivity = 1.0f;
            chipSensitivity.setText("灵敏度：自适应");
            tip = "已切换为【智能自适应模式】：标准均衡推荐";
        }
        Toast.makeText(this, tip, Toast.LENGTH_SHORT).show();
        reprocessAllPagesWithSensitivity();
    }

    private void reprocessAllPagesWithSensitivity() {
        setBusy(true, "正在以新灵敏度自适应重新识别...");

        executor.execute(() -> {
            int totalAllMarks = 0;
            for (PageItem item : pageItems) {
                if (item.originalBitmap != null) {
                    for (ParkingRecord r : item.records) {
                        for (int c = 0; c < 14; c++) {
                            r.setShiftChecked(c, false);
                        }
                    }
                    CheckmarkDetector.DetectionResult res = CheckmarkDetector.detect(
                        item.originalBitmap, item.records, currentSensitivity, 0, 0, showHelperGrid, showBadge
                    );
                    item.detectionResult = res;
                    if (res != null) {
                        item.records = res.records;
                        item.detectedMarksCount = res.totalMarkCount;
                        totalAllMarks += item.detectedMarksCount;
                        item.annotatedBitmap = CheckmarkDetector.renderOverlay(
                            item.originalBitmap, item.records, res.grid, showHelperGrid, showBadge, 0, 0
                        );
                    }
                }
            }

            final int finalTotal = totalAllMarks;
            mainHandler.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                updateAllCardsUI();
                switchViewMode(currentViewMode);
                setBusy(false, "已就绪 (" + finalTotal + "处打钩)");
                Toast.makeText(MainActivity.this, "识别完成：当前灵敏度下共识别 " + finalTotal + " 处打钩", Toast.LENGTH_SHORT).show();
            });
        });
    }

    private void updateAllCardsUI() {
        for (int i = 0; i < pageItems.length; i++) {
            PageItem item = pageItems[i];
            Bitmap shown = (item.annotatedBitmap != null) ? item.annotatedBitmap : item.originalBitmap;
            if (shown != null) {
                cardImages[i].setImageBitmap(shown);
                cardImages[i].setVisibility(View.VISIBLE);
                cardPlaceholders[i].setVisibility(View.GONE);
                cardMarks[i].setText(item.detectedMarksCount + " 处打钩");
            } else {
                cardImages[i].setVisibility(View.GONE);
                cardPlaceholders[i].setVisibility(View.VISIBLE);
                cardMarks[i].setText("未载入");
            }
        }
    }

    private void refreshAllImageOverlays() {
        for (PageItem item : pageItems) {
            if (item.originalBitmap != null) {
                CheckmarkDetector.TableGrid grid = (item.detectionResult != null) ? item.detectionResult.grid : null;
                item.annotatedBitmap = CheckmarkDetector.renderOverlay(
                    item.originalBitmap, item.records, grid, showHelperGrid, showBadge, 0, 0
                );
            }
        }
        updateAllCardsUI();
        if (currentViewMode >= 1 && currentViewMode <= pageItems.length) {
            PageItem cur = pageItems[currentViewMode - 1];
            if (cur.annotatedBitmap != null) {
                ivPreview.setImageBitmap(cur.annotatedBitmap);
            }
        }
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

        int[] hit = CheckmarkDetector.getCellAtPoint(bmX, bmY, imgW, imgH, grid, currentOffsetX, currentOffsetY, item.records.size());
        if (hit != null) {
            int row = hit[0];
            int col = hit[1];
            if (row >= 0 && row < item.records.size()) {
                ParkingRecord rec = item.records.get(row);
                rec.toggleShift(col);
                boolean nowChecked = rec.isShiftChecked(col);
                String cell = rec.getExcelCell(col);
                String shift = ShiftConstants.SHIFT_NAMES[col];

                // 重新计数
                int newCount = 0;
                for (ParkingRecord r : item.records) newCount += r.getCheckedCount();
                item.detectedMarksCount = newCount;

                refreshAllImageOverlays();
                adapter.notifyItemChanged(row);
                updateStatsDisplay();
                updateSummaryText();

                // 采集用户手动微调作为训练数据并累计
                if (grid != null) {
                    RectF cellRect = grid.getCellRect(row, col, currentOffsetX, currentOffsetY, imgW, imgH);
                    TrainingDataCollector.getInstance().recordManualCorrection(
                        MainActivity.this, item.originalBitmap, cellRect, row, col,
                        rec.getName(), shift, cell, nowChecked
                    );
                    updateTrainingDataButtonBadge();
                }

                String state = nowChecked ? "已补勾 [√]" : "已取消";
                Toast.makeText(this, rec.getName() + " · " + shift + " (" + cell + ") -> " + state + " (已累计为训练样本)", Toast.LENGTH_SHORT).show();
            }
        }
    }

    /**
     * 导入一张或多张图片：从当前聚焦页开始 (纵览模式从第 1 页开始)，按选择顺序依次填入后续页面。
     * 超出剩余页数的图片会被忽略并提示。
     */
    private void importImages(List<Uri> uris) {
        if (uris == null || uris.isEmpty() || rejectIfBusy()) return;

        final int startIdx = (currentViewMode >= 1 && currentViewMode <= pageItems.length) ? (currentViewMode - 1) : 0;
        final int capacity = pageItems.length - startIdx;
        final List<Uri> accepted = new ArrayList<>(uris.subList(0, Math.min(uris.size(), capacity)));
        final int skipped = uris.size() - accepted.size();
        final int total = accepted.size();

        setBusy(true, total > 1 ? "正在识别第 1/" + total + " 张图片..." : "正在分析自定义图片...");

        executor.execute(() -> {
            List<String> doneNames = new ArrayList<>();
            List<String> unmergedNames = new ArrayList<>();
            int failed = 0;

            for (int i = 0; i < total; i++) {
                PageItem target = pageItems[startIdx + i];
                if (i > 0) {
                    final String progress = "正在识别第 " + (i + 1) + "/" + total + " 张图片...";
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
            mainHandler.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                updateAllCardsUI();
                // 单张：直接聚焦该页便于微调；多张：回到纵览查看全部结果
                if (total == 1) {
                    switchViewMode(failedCount == 0 ? startIdx + 1 : currentViewMode);
                } else {
                    switchViewMode(0);
                }

                StringBuilder msg = new StringBuilder();
                if (!doneNames.isEmpty()) {
                    msg.append("已识别 ").append(doneNames.size()).append(" 张：").append(String.join("、", doneNames));
                }
                setBusy(false, doneNames.isEmpty() ? "图片读取失败" : "已导入 " + doneNames.size() + " 张图片");

                if (failedCount == 0 && skipped == 0 && unmergedNames.isEmpty()) {
                    Toast.makeText(MainActivity.this, msg.toString(), Toast.LENGTH_LONG).show();
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
                       .append(" 张。如需导入更多，请先切到「全部」或靠前的页面再选择。");
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
            Toast.makeText(this, "启动相机失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void showEditRecordDialog(ParkingRecord record, int position) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("编辑人员信息 (序号 " + record.getIndex() + ")");

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(50, 30, 50, 10);

        final EditText etName = new EditText(this);
        etName.setHint("姓名 (如: 刘文虎)");
        etName.setText(record.getName());
        layout.addView(etName);

        final EditText etPlate = new EditText(this);
        etPlate.setHint("车牌号 (如: 川F08CT9)");
        etPlate.setText(record.getPlateNumber());
        layout.addView(etPlate);

        builder.setView(layout);
        builder.setPositiveButton("保存修改", (dialog, which) -> {
            String newName = etName.getText().toString().trim();
            String newPlate = etPlate.getText().toString().trim();
            if (!newName.isEmpty()) record.setName(newName);
            if (!newPlate.isEmpty()) record.setPlateNumber(newPlate);
            adapter.notifyItemChanged(position);
            updateSummaryText();
            Toast.makeText(this, "已更新: " + record.getName() + " (" + record.getPlateNumber() + ")", Toast.LENGTH_SHORT).show();
        });
        builder.setNegativeButton("取消", null);
        builder.show();
    }

    private List<ParkingRecord> getCombinedRecords(int... pages) {
        List<ParkingRecord> combined = new ArrayList<>();
        for (int p : pages) {
            if (p >= 1 && p <= pageItems.length) {
                combined.addAll(pageItems[p - 1].records);
            }
        }
        return combined;
    }

    /**
     * 第 1~3 页始终参与导出 (与原先一致)；第 4 页起只合并用户导入且 OCR 识别出序号的页面，
     * 避免示例图或序号未知的照片按默认序号 1~39 错填到别人名下。
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

    private void updateStatsDisplay() {
        int loadedPages = 0;
        for (PageItem item : pageItems) {
            if (item.originalBitmap != null) loadedPages++;
        }
        tvStatPages.setText(loadedPages + " / " + pageItems.length + " 张");

        if (currentViewMode == 0) {
            int totalAllMarks = 0;
            for (PageItem item : pageItems) totalAllMarks += item.detectedMarksCount;

            // 出勤人数与导出口径一致：各页按序号合并后统计
            List<ParkingRecord> fullRoster = getExportRecords();
            int attended = 0;
            for (ParkingRecord r : fullRoster) {
                if (r.hasAnyChecked()) attended++;
            }
            tvStatMarks.setText(totalAllMarks + " 处");
            tvStatPersons.setText(attended + " / " + fullRoster.size() + " 人");
            tvStatusBadge.setText("全部图片 (" + loadedPages + "张纵览)");
        } else {
            PageItem item = pageItems[currentViewMode - 1];
            int attended = 0;
            for (ParkingRecord r : item.records) {
                if (r.hasAnyChecked()) attended++;
            }
            tvStatMarks.setText(item.detectedMarksCount + " 处");
            tvStatPersons.setText(attended + " / " + item.records.size() + " 人");
            tvStatusBadge.setText(item.title != null ? item.title : item.tabName);
        }
    }

    private void updateSummaryText() {
        StringBuilder sb = new StringBuilder();
        sb.append("📋 【").append(ShiftConstants.TABLE_TITLE).append(" - 打钩识别与Excel核对报表】\n");
        sb.append("显示视图: ").append(currentViewMode == 0 ? "全部图片 (纵览模式)" : pageItems[currentViewMode - 1].title).append("\n");
        sb.append("--------------------------------------------------\n\n");

        List<ParkingRecord> targetRecords = (currentViewMode == 0)
            ? getExportRecords()
            : pageItems[currentViewMode - 1].records;

        int totalChecks = 0;
        int[] shiftCounts = new int[14];

        for (ParkingRecord r : targetRecords) {
            if (r.hasAnyChecked()) {
                totalChecks += r.getCheckedCount();
                sb.append(String.format("• 序号%02d: %s (%s) [Excel第%d行]\n",
                    r.getIndex(), r.getName(), r.getPlateNumber(), r.getExcelRow()));

                for (int c = 0; c < 14; c++) {
                    if (r.isShiftChecked(c)) {
                        shiftCounts[c]++;
                        sb.append(String.format("   ↳ %s -> 单元格: 【%s】 (打钩 √)\n",
                            ShiftConstants.SHIFT_NAMES[c], r.getExcelCell(c)));
                    }
                }
                sb.append("\n");
            }
        }

        sb.append("--------------------------------------------------\n");
        sb.append("📊 【按班次出勤统计】\n");
        for (int d = 0; d < 7; d++) {
            int dayShift = shiftCounts[d * 2];
            int nightShift = shiftCounts[d * 2 + 1];
            sb.append(String.format("• %s: 白班 %d 人 (列%s), 夜班 %d 人 (列%s)\n",
                ShiftConstants.DAYS[d], dayShift, ShiftConstants.EXCEL_COLUMNS[d * 2],
                nightShift, ShiftConstants.EXCEL_COLUMNS[d * 2 + 1]));
        }

        int allPagesChecks = 0;
        int loadedPages = 0;
        for (PageItem item : pageItems) {
            allPagesChecks += item.detectedMarksCount;
            if (item.originalBitmap != null) loadedPages++;
        }

        sb.append(String.format("\n📌 当前视图打钩人次: %d 次  |  %d张图全部打钩总计: %d 次\n", totalChecks, loadedPages, allPagesChecks));
        summaryText = sb.toString();
    }

    /**
     * 导出入口：底部弹层汇总全部导出方式 (停车情况表副本填1 / 导入表格填1 / 复制报表)
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
        addExportOption(sheet, options, R.drawable.ic_content_copy, "复制文字报表",
            "复制每人打钩情况及对应 Excel 单元格到剪贴板", this::copySummaryToClipboard);

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

    private void copySummaryToClipboard() {
        if (summaryText == null || summaryText.isEmpty()) {
            updateSummaryText();
        }
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData clip = ClipData.newPlainText("打钩识别结果", summaryText);
        cm.setPrimaryClip(clip);
        Toast.makeText(this, "已复制 Excel 映射结果到剪贴板！", Toast.LENGTH_SHORT).show();
    }

    private void processExcelFilling(Uri userExcelUri) {
        Toast.makeText(this, "正在按输入表格填入 1 并生成导出文件...", Toast.LENGTH_SHORT).show();
        setBusy(true, "正在执行按表填1并导出...");

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
                updateStatsDisplay();
                if (result.success && result.outputFile != null) {
                    // 自动将现场已核对通过的表格累计入训练数据库
                    autoRecordVerifiedPagesToDataset();

                    Toast.makeText(MainActivity.this, "已按输入表格成功填入 " + result.filledMarksCount + " 处 1，正在调起导出！", Toast.LENGTH_LONG).show();
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

    private void autoRecordVerifiedPagesToDataset() {
        for (PageItem pi : pageItems) {
            if (pi.originalBitmap != null && pi.detectionResult != null && pi.detectionResult.grid != null) {
                TrainingDataCollector.getInstance().recordBatchVerifiedPage(
                    MainActivity.this, pi.originalBitmap, pi.detectionResult.grid, pi.records
                );
            }
        }
        updateTrainingDataButtonBadge();
    }

    private void updateTrainingDataButtonBadge() {
        TrainingDataCollector.getInstance().getStatsAsync(MainActivity.this, stats -> mainHandler.post(() -> {
            MenuItem item = toolbar.getMenu().findItem(R.id.action_training);
            if (item != null) {
                item.setTitle("训练集 (" + stats.totalSamples + ")");
            }
        }));
    }

    private void showTrainingDataDialog() {
        executor.execute(() -> {
            TrainingDataCollector.DatasetStats stats = TrainingDataCollector.getInstance().getStats(MainActivity.this);
            mainHandler.post(() -> {
                if (isFinishing() || isDestroyed()) return;

                StringBuilder sb = new StringBuilder();
                sb.append("📊 本地已累计沉淀训练样本：").append(stats.totalSamples).append(" 组\n");
                sb.append("• 真实打钩正样本：").append(stats.positiveChecks).append(" 个\n");
                sb.append("• 空白/杂斑负样本：").append(stats.negativeBlanks).append(" 个\n");
                sb.append("• 用户人工纠错记录：").append(stats.manualCorrections).append(" 次\n\n");
                sb.append("🎯 算法自学习与自校准状态：\n");
                sb.append("• 笔迹对比度阈值：").append(stats.calibratedMinContrast)
                  .append(stats.isCalibrated ? " (已自学习优化)" : " (默认基准)").append("\n\n");
                sb.append("💡 说明：平时拍摄的照片和您做出的每一次打钩微调、修正以及导出，都会被自动累计为现场高价值训练数据。\n\n您可直接在手机上一键【自校准算法】，也可【打包导出 ZIP】分享给算法团队持续微调神经网络！");

                AlertDialog.Builder builder = new AlertDialog.Builder(MainActivity.this)
                    .setTitle("🎓 训练数据累计与算法自优化")
                    .setMessage(sb.toString())
                    .setPositiveButton("⚡ 立即自校准算法", (dialog, which) -> {
                        executor.execute(() -> {
                            String res = TrainingDataCollector.getInstance().calibrateThresholds(MainActivity.this);
                            int optContrast = TrainingDataCollector.getCalibratedMinContrast(MainActivity.this);
                            CheckmarkDetector.setCalibratedMinContrast(optContrast);
                            mainHandler.post(() -> {
                                updateTrainingDataButtonBadge();
                                reprocessAllPagesWithSensitivity();
                                new AlertDialog.Builder(MainActivity.this)
                                    .setTitle("算法优化结果")
                                    .setMessage(res)
                                    .setPositiveButton("太棒了", null)
                                    .show();
                            });
                        });
                    })
                    .setNeutralButton("📦 打包导出 (ZIP)", (dialog, which) -> {
                        TrainingDataCollector.getInstance().exportDatasetZip(MainActivity.this);
                    })
                    .setNegativeButton("关闭", null);

                builder.show();
            });
        });
    }
}
