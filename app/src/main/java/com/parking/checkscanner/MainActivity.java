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
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.cardview.widget.CardView;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    public static class PageItem {
        public int pageId; // 1, 2, 3, 4, 5
        public String title;
        public String tabName;
        public String assetFileName;
        public int startRowIndex;
        public int rowCount;
        public List<ParkingRecord> records;
        public Bitmap originalBitmap;
        public Bitmap annotatedBitmap;
        public CheckmarkDetector.DetectionResult detectionResult;
        public int detectedMarksCount = 0;
        public int ocrCount = 0;

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

    // 5个实拍与示例图页面数据
    private PageItem[] pageItems = new PageItem[] {
        new PageItem(1, "实拍图 1 · 第1页 (序号 1~39)", "实拍P1 (1~39)", "sample_p1.jpg", 1, 39),
        new PageItem(2, "实拍图 2 · 第2页 (序号 40~77)", "实拍P2 (40~77)", "sample_p2.jpg", 40, 38),
        new PageItem(3, "实拍图 3 · 第3页 (序号 78~115)", "实拍P3 (78~115)", "sample_p3.jpg", 78, 38),
        new PageItem(4, "实拍图 4 · 备选第1页 (序号 1~39)", "实拍P4 (备选)", "sample_p4.jpg", 1, 39),
        new PageItem(5, "原始示例表 (序号 1~39)", "原始示例表", "sample_parking_table.jpg", 1, 39)
    };

    // 0 表示全部图片纵览模式，1~5 表示聚焦对应单图
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
    private Uri cameraImageUri = null;
    private String summaryText = "";

    // UI 组件
    private Button btnSample, btnCamera, btnGallery, btnSensitivity, btnExportExcel, btnFillExcel, btnCopy;
    private Button btnPageAll, btnPage1, btnPage2, btnPage3, btnPage4, btnPageSample;
    private Button tabImage, tabRecords;
    private Button btnCheckUpdate, btnTrainingData;
    private LinearLayout layoutImageView, layoutRecordsView;
    private TextView tvStatMarks, tvStatPersons, tvStatusBadge, tvPreviewTip;
    private Button btnToggleBadge, btnToggleGrid;
    private EditText etSearch;
    private RecyclerView rvRecords;

    // 单图预览与多卡片预览
    private ImageView ivPreview;
    private LinearLayout llAllImagesCards;
    private CardView cardImg1, cardImg2, cardImg3, cardImg4, cardImg5;
    private ImageView ivCard1, ivCard2, ivCard3, ivCard4, ivCard5;
    private TextView tvCardTitle1, tvCardTitle2, tvCardTitle3, tvCardTitle4, tvCardTitle5;
    private TextView tvCardMarks1, tvCardMarks2, tvCardMarks3, tvCardMarks4, tvCardMarks5;

    private ActivityResultLauncher<Intent> galleryLauncher;
    private ActivityResultLauncher<Uri> cameraLauncher;
    private ActivityResultLauncher<String> cameraPermissionLauncher;
    private ActivityResultLauncher<Intent> excelPickerLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        initViews();
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
        updateToggleBadgeButton();
        updatePageNavUI(0);

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
        btnSample = findViewById(R.id.btn_sample);
        btnCamera = findViewById(R.id.btn_camera);
        btnGallery = findViewById(R.id.btn_gallery);
        btnSensitivity = findViewById(R.id.btn_sensitivity);
        btnExportExcel = findViewById(R.id.btn_export_excel);
        btnFillExcel = findViewById(R.id.btn_fill_excel);
        btnCopy = findViewById(R.id.btn_copy);

        btnPageAll = findViewById(R.id.btn_page_all);
        btnPage1 = findViewById(R.id.btn_page_1);
        btnPage2 = findViewById(R.id.btn_page_2);
        btnPage3 = findViewById(R.id.btn_page_3);
        btnPage4 = findViewById(R.id.btn_page_4);
        btnPageSample = findViewById(R.id.btn_page_sample);

        tabImage = findViewById(R.id.tab_image);
        tabRecords = findViewById(R.id.tab_records);

        layoutImageView = findViewById(R.id.layout_image_view);
        layoutRecordsView = findViewById(R.id.layout_records_view);

        tvStatMarks = findViewById(R.id.tv_stat_marks);
        tvStatPersons = findViewById(R.id.tv_stat_persons);
        tvStatusBadge = findViewById(R.id.tv_status_badge);
        tvPreviewTip = findViewById(R.id.tv_preview_tip);

        btnToggleBadge = findViewById(R.id.btn_toggle_badge);
        btnToggleGrid = findViewById(R.id.btn_toggle_grid);
        btnCheckUpdate = findViewById(R.id.btn_check_update);
        btnTrainingData = findViewById(R.id.btn_training_data);

        etSearch = findViewById(R.id.et_search);
        rvRecords = findViewById(R.id.rv_records);

        ivPreview = findViewById(R.id.iv_preview);
        llAllImagesCards = findViewById(R.id.ll_all_images_cards);

        cardImg1 = findViewById(R.id.card_img_1);
        cardImg2 = findViewById(R.id.card_img_2);
        cardImg3 = findViewById(R.id.card_img_3);
        cardImg4 = findViewById(R.id.card_img_4);
        cardImg5 = findViewById(R.id.card_img_5);

        ivCard1 = findViewById(R.id.iv_card_1);
        ivCard2 = findViewById(R.id.iv_card_2);
        ivCard3 = findViewById(R.id.iv_card_3);
        ivCard4 = findViewById(R.id.iv_card_4);
        ivCard5 = findViewById(R.id.iv_card_5);

        tvCardTitle1 = findViewById(R.id.tv_card_title_1);
        tvCardTitle2 = findViewById(R.id.tv_card_title_2);
        tvCardTitle3 = findViewById(R.id.tv_card_title_3);
        tvCardTitle4 = findViewById(R.id.tv_card_title_4);
        tvCardTitle5 = findViewById(R.id.tv_card_title_5);

        tvCardMarks1 = findViewById(R.id.tv_card_marks_1);
        tvCardMarks2 = findViewById(R.id.tv_card_marks_2);
        tvCardMarks3 = findViewById(R.id.tv_card_marks_3);
        tvCardMarks4 = findViewById(R.id.tv_card_marks_4);
        tvCardMarks5 = findViewById(R.id.tv_card_marks_5);
    }

    private void setupLaunchers() {
        galleryLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    Uri uri = result.getData().getData();
                    if (uri != null) {
                        loadCustomImage(uri);
                    }
                }
            }
        );

        cameraLauncher = registerForActivityResult(
            new ActivityResultContracts.TakePicture(),
            success -> {
                if (success && cameraImageUri != null) {
                    loadCustomImage(cameraImageUri);
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
        btnSample.setOnClickListener(v -> loadAllSampleImages());

        if (btnSensitivity != null) {
            btnSensitivity.setOnClickListener(v -> cycleSensitivity());
        }

        btnCamera.setOnClickListener(v -> {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                startCameraCapture();
            } else {
                cameraPermissionLauncher.launch(Manifest.permission.CAMERA);
            }
        });

        btnGallery.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.setType("image/*");
            galleryLauncher.launch(intent);
        });

        if (btnFillExcel != null) {
            btnFillExcel.setOnClickListener(v -> showFillExcelDialog());
        }

        btnExportExcel.setOnClickListener(v -> exportExcel());
        btnCopy.setOnClickListener(v -> copySummaryToClipboard());

        if (btnTrainingData != null) {
            btnTrainingData.setOnClickListener(v -> showTrainingDataDialog());
        }

        if (btnCheckUpdate != null) {
            btnCheckUpdate.setOnClickListener(v -> AppUpdateManager.getInstance().checkUpdate(this, true));
            btnCheckUpdate.setOnLongClickListener(v -> {
                AppUpdateManager.getInstance().showConfigureUrlDialog(this);
                return true;
            });
        }

        // 分页与全图切换导航
        btnPageAll.setOnClickListener(v -> switchViewMode(0));
        btnPage1.setOnClickListener(v -> switchViewMode(1));
        btnPage2.setOnClickListener(v -> switchViewMode(2));
        btnPage3.setOnClickListener(v -> switchViewMode(3));
        btnPage4.setOnClickListener(v -> switchViewMode(4));
        btnPageSample.setOnClickListener(v -> switchViewMode(5));

        // 点击全图列表中的卡片，可直接快捷进入该图的单图聚焦微调视图
        if (cardImg1 != null) cardImg1.setOnClickListener(v -> switchViewMode(1));
        if (cardImg2 != null) cardImg2.setOnClickListener(v -> switchViewMode(2));
        if (cardImg3 != null) cardImg3.setOnClickListener(v -> switchViewMode(3));
        if (cardImg4 != null) cardImg4.setOnClickListener(v -> switchViewMode(4));
        if (cardImg5 != null) cardImg5.setOnClickListener(v -> switchViewMode(5));

        // Tab 切换 (Tab 0: 效果预览 / Tab 1: 核对名单)
        tabImage.setOnClickListener(v -> switchTab(0));
        tabRecords.setOnClickListener(v -> switchTab(1));

        // 标号显隐切换 (只留框体 vs 标号+框体)
        btnToggleBadge.setOnClickListener(v -> {
            showBadge = !showBadge;
            updateToggleBadgeButton();
            refreshAllImageOverlays();
        });

        // 辅助网格切换
        btnToggleGrid.setOnClickListener(v -> {
            showHelperGrid = !showHelperGrid;
            btnToggleGrid.setText(showHelperGrid ? "📐 隐藏网格" : "📐 辅助网格");
            refreshAllImageOverlays();
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
                if (currentViewMode >= 1 && currentViewMode <= 5) {
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

    private void updateToggleBadgeButton() {
        if (btnToggleBadge != null) {
            if (showBadge) {
                btnToggleBadge.setText("🏷️ 隐藏标号 (只留框体)");
                if (tvPreviewTip != null) {
                    tvPreviewTip.setText("💡 标号模式 (含Excel单元格坐标)");
                }
            } else {
                btnToggleBadge.setText("🏷️ 显示标号 (坐标)");
                if (tvPreviewTip != null) {
                    tvPreviewTip.setText("💡 仅显示框体 (原图笔画清晰)");
                }
            }
        }
    }

    private void switchViewMode(int mode) {
        currentViewMode = mode;
        updatePageNavUI(mode);

        if (mode == 0) {
            // 全部图片纵览模式
            if (llAllImagesCards != null) llAllImagesCards.setVisibility(View.VISIBLE);
            if (ivPreview != null) ivPreview.setVisibility(View.GONE);
            adapter.setRecords(getCombinedRecords(1, 2, 3));
            tvStatusBadge.setText("全部图片 (5张纵览)");
            tvStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.checked_green));
        } else {
            // 单图聚焦模式
            if (llAllImagesCards != null) llAllImagesCards.setVisibility(View.GONE);
            if (ivPreview != null) ivPreview.setVisibility(View.VISIBLE);

            PageItem item = pageItems[mode - 1];
            if (item.annotatedBitmap != null) {
                ivPreview.setImageBitmap(item.annotatedBitmap);
            } else if (item.originalBitmap != null) {
                ivPreview.setImageBitmap(item.originalBitmap);
            }
            adapter.setRecords(item.records);
            tvStatusBadge.setText(item.title != null ? item.title : item.tabName);
            tvStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.checked_green));
        }

        updateStatsDisplay();
        updateSummaryText();
    }

    private void updatePageNavUI(int mode) {
        setNavBtnStyle(btnPageAll, mode == 0);
        setNavBtnStyle(btnPage1, mode == 1);
        setNavBtnStyle(btnPage2, mode == 2);
        setNavBtnStyle(btnPage3, mode == 3);
        setNavBtnStyle(btnPage4, mode == 4);
        setNavBtnStyle(btnPageSample, mode == 5);
    }

    private void setNavBtnStyle(Button btn, boolean isSelected) {
        if (btn == null) return;
        btn.setBackgroundResource(isSelected ? R.drawable.bg_button_primary : R.drawable.bg_button_secondary);
        btn.setTextColor(ContextCompat.getColor(this, isSelected ? android.R.color.white : R.color.primary));
    }

    private void switchTab(int tabIndex) {
        layoutImageView.setVisibility(tabIndex == 0 ? View.VISIBLE : View.GONE);
        layoutRecordsView.setVisibility(tabIndex == 1 ? View.VISIBLE : View.GONE);

        updateTabButton(tabImage, tabIndex == 0);
        updateTabButton(tabRecords, tabIndex == 1);
    }

    private void updateTabButton(Button btn, boolean isSelected) {
        if (isSelected) {
            btn.setBackgroundResource(R.drawable.bg_button_primary);
            btn.setTextColor(ContextCompat.getColor(this, android.R.color.white));
        } else {
            btn.setBackgroundResource(R.drawable.bg_button_secondary);
            btn.setTextColor(ContextCompat.getColor(this, R.color.primary));
        }
    }

    private void loadAllSampleImages() {
        tvStatusBadge.setText("正在分析全部实拍与示例图...");
        tvStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.primary));

        executor.execute(() -> {
            int totalAllMarks = 0;

            for (int i = 0; i < pageItems.length; i++) {
                PageItem item = pageItems[i];
                try {
                    InputStream is = getAssets().open(item.assetFileName);
                    Bitmap bitmap = BitmapFactory.decodeStream(is);
                    is.close();
                    item.originalBitmap = bitmap;

                    // 1. 物理表格线与打钩检测 (基于拓扑和污渍过滤算法)
                    CheckmarkDetector.DetectionResult res = CheckmarkDetector.detect(
                        bitmap, item.records, currentSensitivity, 0, 0, showHelperGrid, showBadge
                    );
                    item.detectionResult = res;

                    if (res != null) {
                        item.records = res.records;
                        item.detectedMarksCount = res.totalMarkCount;
                        totalAllMarks += item.detectedMarksCount;

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
                            }
                        } catch (Exception e) {
                            Log.e("MainActivity", "OCR 处理异常: " + e.getMessage(), e);
                        }

                        // 3. 生成展示图片 (遵循 showBadge=false，仅框展示)
                        item.annotatedBitmap = CheckmarkDetector.renderOverlay(
                            bitmap, item.records, res.grid, showHelperGrid, showBadge, 0, 0
                        );
                    }
                } catch (Exception e) {
                    Log.e("MainActivity", "加载 " + item.assetFileName + " 失败: " + e.getMessage(), e);
                }
            }

            final int finalTotalMarks = totalAllMarks;
            mainHandler.post(() -> {
                updateAllCardsUI();
                switchViewMode(currentViewMode);
                updateStatsDisplay();
                updateSummaryText();

                tvStatusBadge.setText("5张图已就绪 (仅框)");
                tvStatusBadge.setTextColor(ContextCompat.getColor(MainActivity.this, R.color.checked_green));
                Toast.makeText(MainActivity.this, "已载入全部 5 张实拍与示例图，每张图仅展示打钩框体！", Toast.LENGTH_SHORT).show();
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
        sensitivityMode = (sensitivityMode + 1) % 3;
        String tip;
        if (sensitivityMode == 1) {
            currentSensitivity = 1.25f;
            btnSensitivity.setText("🔍 灵敏度: 增强");
            tip = "已切换为【增强模式】：更灵敏捕获浅铅笔/淡笔迹打钩";
        } else if (sensitivityMode == 2) {
            currentSensitivity = 0.85f;
            btnSensitivity.setText("🛡️ 灵敏度: 严格");
            tip = "已切换为【严格模式】：抗纸张重度反光与透印杂斑";
        } else {
            currentSensitivity = 1.0f;
            btnSensitivity.setText("⚡ 灵敏度: 自适应");
            tip = "已切换为【智能自适应模式】：标准均衡推荐";
        }
        Toast.makeText(this, tip, Toast.LENGTH_SHORT).show();
        reprocessAllPagesWithSensitivity();
    }

    private void reprocessAllPagesWithSensitivity() {
        tvStatusBadge.setText("正在以新灵敏度自适应重新识别...");
        tvStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.primary));

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
                updateStatsDisplay();
                updateSummaryText();
                if (adapter != null) {
                    adapter.setRecords(getCombinedRecords(1, 2, 3));
                }
                tvStatusBadge.setText("已就绪 (" + finalTotal + "处打钩)");
                tvStatusBadge.setTextColor(ContextCompat.getColor(MainActivity.this, R.color.checked_green));
                Toast.makeText(MainActivity.this, "识别完成：当前灵敏度下共识别 " + finalTotal + " 处打钩", Toast.LENGTH_SHORT).show();
            });
        });
    }

    private void updateAllCardsUI() {
        if (ivCard1 != null && pageItems[0].annotatedBitmap != null) {
            ivCard1.setImageBitmap(pageItems[0].annotatedBitmap);
            if (tvCardMarks1 != null) tvCardMarks1.setText(pageItems[0].detectedMarksCount + " 处打钩 [√]");
        }
        if (ivCard2 != null && pageItems[1].annotatedBitmap != null) {
            ivCard2.setImageBitmap(pageItems[1].annotatedBitmap);
            if (tvCardMarks2 != null) tvCardMarks2.setText(pageItems[1].detectedMarksCount + " 处打钩 [√]");
        }
        if (ivCard3 != null && pageItems[2].annotatedBitmap != null) {
            ivCard3.setImageBitmap(pageItems[2].annotatedBitmap);
            if (tvCardMarks3 != null) tvCardMarks3.setText(pageItems[2].detectedMarksCount + " 处打钩 [√]");
        }
        if (ivCard4 != null && pageItems[3].annotatedBitmap != null) {
            ivCard4.setImageBitmap(pageItems[3].annotatedBitmap);
            if (tvCardMarks4 != null) tvCardMarks4.setText(pageItems[3].detectedMarksCount + " 处打钩 [√]");
        }
        if (ivCard5 != null && pageItems[4].annotatedBitmap != null) {
            ivCard5.setImageBitmap(pageItems[4].annotatedBitmap);
            if (tvCardMarks5 != null) tvCardMarks5.setText(pageItems[4].detectedMarksCount + " 处打钩 [√]");
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
        if (currentViewMode >= 1 && currentViewMode <= 5) {
            PageItem cur = pageItems[currentViewMode - 1];
            if (cur.annotatedBitmap != null) {
                ivPreview.setImageBitmap(cur.annotatedBitmap);
            }
        }
    }

    private void handleImageTouch(float touchX, float touchY) {
        if (currentViewMode < 1 || currentViewMode > 5) return;
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

    private void loadCustomImage(Uri uri) {
        tvStatusBadge.setText("正在分析自定义图片...");
        tvStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.primary));

        executor.execute(() -> {
            try {
                Bitmap bitmap = ImageUtils.loadAndNormalizeBitmap(this, uri);
                if (bitmap == null) {
                    mainHandler.post(() -> Toast.makeText(MainActivity.this, "读取图片失败，请重试", Toast.LENGTH_SHORT).show());
                    return;
                }

                int targetPageIdx = (currentViewMode >= 1 && currentViewMode <= 5) ? (currentViewMode - 1) : 0;
                PageItem targetItem = pageItems[targetPageIdx];
                targetItem.originalBitmap = bitmap;

                CheckmarkDetector.DetectionResult res = CheckmarkDetector.detect(
                    bitmap, targetItem.records, currentSensitivity, 0, 0, showHelperGrid, showBadge
                );
                targetItem.detectionResult = res;

                if (res != null) {
                    targetItem.records = res.records;
                    targetItem.detectedMarksCount = res.totalMarkCount;

                    try {
                        TableOcrScanner.OcrResult ocrRes = TableOcrScanner.scanTable(bitmap, res.grid, res.records);
                        if (ocrRes != null && ocrRes.success && ocrRes.records != null && !ocrRes.records.isEmpty()) {
                            for (int r = 0; r < Math.min(res.records.size(), ocrRes.records.size()); r++) {
                                ParkingRecord original = res.records.get(r);
                                ParkingRecord ocrRec = ocrRes.records.get(r);
                                ocrRec.copyShiftsFrom(original);
                            }
                            res.records = ocrRes.records;
                            targetItem.records = ocrRes.records;
                            targetItem.ocrCount = ocrRes.recognizedItemCount;
                        }
                    } catch (Exception e) {
                        Log.e("MainActivity", "OCR异常: " + e.getMessage(), e);
                    }

                    targetItem.annotatedBitmap = CheckmarkDetector.renderOverlay(
                        bitmap, targetItem.records, res.grid, showHelperGrid, showBadge, 0, 0
                    );
                }

                mainHandler.post(() -> {
                    updateAllCardsUI();
                    switchViewMode(targetPageIdx + 1);
                    Toast.makeText(MainActivity.this, targetItem.tabName + " 载入识别完成！", Toast.LENGTH_SHORT).show();
                });
            } catch (Exception e) {
                e.printStackTrace();
                mainHandler.post(() -> Toast.makeText(MainActivity.this, "处理图片错误: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        });
    }

    private void startCameraCapture() {
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

    private void updateStatsDisplay() {
        if (currentViewMode == 0) {
            int totalAllMarks = 0;
            for (PageItem item : pageItems) totalAllMarks += item.detectedMarksCount;

            List<ParkingRecord> fullRoster = getCombinedRecords(1, 2, 3);
            int attended = 0;
            for (ParkingRecord r : fullRoster) {
                if (r.hasAnyChecked()) attended++;
            }
            tvStatMarks.setText(totalAllMarks + " 处");
            tvStatPersons.setText(attended + " / 115 人");
            tvStatusBadge.setText("全部图片 (5张纵览)");
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
            ? getCombinedRecords(1, 2, 3) 
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

        int all5Checks = 0;
        for (PageItem item : pageItems) all5Checks += item.detectedMarksCount;

        sb.append(String.format("\n📌 当前视图打钩人次: %d 次  |  5张图全部打钩总计: %d 次\n", totalChecks, all5Checks));
        summaryText = sb.toString();
    }

    private void exportExcel() {
        try {
            // 合并实拍第1页(1~39)、第2页(40~77)与第3页(78~115)完整115人名单导出 (勾换成1)
            List<ParkingRecord> allRecords = getCombinedRecords(1, 2, 3);
            File xlsxFile = ExcelExporter.exportToXlsx(this, allRecords);

            // 自动将现场已核对通过的表格累计入训练数据库
            autoRecordVerifiedPagesToDataset();

            Toast.makeText(this, "Excel 导出成功 (勾已换为数字1): " + xlsxFile.getName(), Toast.LENGTH_SHORT).show();
            ExcelExporter.shareFile(this, xlsxFile, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(this, "导出 Excel 失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
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

    private void showFillExcelDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("📥 导入表格填1并导出");

        List<ParkingRecord> allRecords = getCombinedRecords(1, 2, 3);
        int attended = 0;
        int totalMarks = 0;
        for (ParkingRecord r : allRecords) {
            if (r.hasAnyChecked()) attended++;
            totalMarks += r.getCheckedCount();
        }

        String msg = "将按照图片识别的打钩情况，把打钩全部换成数字 1，位置完全按照输入的表格填入并直接导出：\n\n"
                   + "• 识别打钩总数: " + totalMarks + " 处 (将填入1)\n"
                   + "• 涉及出勤人员: " + attended + " 人\n\n"
                   + "请选择导入的表格来源：";
        builder.setMessage(msg);

        builder.setPositiveButton("📁 从手机选择表格并导出", (dialog, which) -> {
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.setType("*/*");
            intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[] {
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                "application/vnd.ms-excel",
                "application/octet-stream"
            });
            excelPickerLauncher.launch(intent);
        });

        builder.setNeutralButton("📋 填入内置标准表并导出", (dialog, which) -> {
            processExcelFilling(null);
        });

        builder.setNegativeButton("取消", null);
        builder.show();
    }

    private void processExcelFilling(Uri userExcelUri) {
        Toast.makeText(this, "正在按输入表格填入 1 并生成导出文件...", Toast.LENGTH_SHORT).show();
        if (tvStatusBadge != null) {
            tvStatusBadge.setText("正在执行按表填1并导出...");
            tvStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.primary));
        }

        executor.execute(() -> {
            List<ParkingRecord> allRecords = getCombinedRecords(1, 2, 3);
            ExcelTemplateFiller.FillResult result;
            if (userExcelUri == null) {
                result = ExcelTemplateFiller.fillBuiltInTemplate(this, allRecords);
            } else {
                result = ExcelTemplateFiller.fillUserExcel(this, userExcelUri, allRecords);
            }

            mainHandler.post(() -> {
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
        executor.execute(() -> {
            TrainingDataCollector.DatasetStats stats = TrainingDataCollector.getInstance().getStats(MainActivity.this);
            mainHandler.post(() -> {
                if (btnTrainingData != null) {
                    btnTrainingData.setText("🎓 训练集 (" + stats.totalSamples + ")");
                }
            });
        });
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

                if (stats.totalSamples > 0) {
                    // 支持清空重置
                }

                builder.show();
            });
        });
    }
}
