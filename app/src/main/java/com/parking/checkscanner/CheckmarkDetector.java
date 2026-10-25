package com.parking.checkscanner;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import java.util.ArrayList;
import java.util.List;

public class CheckmarkDetector {

    // 自适应校准对比度（可由 TrainingDataCollector 在线自学习动态优化）
    private static int calibratedMinContrast = 25;

    public static void setCalibratedMinContrast(int val) {
        calibratedMinContrast = Math.max(15, Math.min(35, val));
    }

    public static int getCalibratedMinContrast() {
        return calibratedMinContrast;
    }

    public static class TableGrid {
        public boolean isDynamic = false;
        public List<Integer> hLines = new ArrayList<>();
        public List<int[]> rowVLines = new ArrayList<>();
        public float shiftsX1;
        public float shiftsX2;
        public float colW;
        public float scaleX = 1.0f;
        public float scaleY = 1.0f;
        public float tilt = 0.0f;

        public boolean isPage2 = false;

        public RectF getCellRect(int r, int c, int offsetX, int offsetY, int imgW, int imgH) {
            int y1, y2;
            if (isDynamic && hLines != null && r + 1 < hLines.size()) {
                float col_t = (c + 0.5f) / 14.0f;
                int tiltOffset = Math.round(tilt * col_t);
                y1 = hLines.get(r) - tiltOffset + offsetY;
                y2 = hLines.get(r + 1) - tiltOffset + offsetY;
            } else if (isPage2) {
                // 第2页专属保底行高 (起始于约95px，每行约19px)
                y1 = Math.round((95 + r * 19.0f) * scaleY + offsetY);
                y2 = Math.round((95 + (r + 1) * 19.0f) * scaleY + offsetY);
            } else {
                if (r >= REF_H_LINES.length - 1) return null;
                y1 = Math.round(REF_H_LINES[r] * scaleY + offsetY);
                y2 = Math.round(REF_H_LINES[r + 1] * scaleY + offsetY);
            }
            if (y2 <= y1 || y1 < 0 || y2 >= imgH) return null;

            int x1, x2;
            if (isDynamic && rowVLines != null && r < rowVLines.size()) {
                int[] vLine = rowVLines.get(r);
                if (c + 1 < vLine.length) {
                    x1 = vLine[c] + offsetX;
                    x2 = vLine[c + 1] + offsetX;
                } else {
                    return null;
                }
            } else if (isDynamic && colW > 0) {
                x1 = Math.round(shiftsX1 + c * colW + offsetX);
                x2 = Math.round(shiftsX1 + (c + 1) * colW + offsetX);
            } else {
                float yTop = (isPage2 ? 95 : REF_H_LINES[0]) * scaleY + offsetY;
                float yBot = (isPage2 ? (95 + 38 * 19.0f) : REF_H_LINES[REF_H_LINES.length - 1]) * scaleY + offsetY;
                float midY = (y1 + y2) / 2.0f;
                float t = Math.max(0.0f, Math.min(1.0f, (midY - yTop) / Math.max(1.0f, (yBot - yTop))));
                float topX1 = REF_X_TOP[c] * scaleX + offsetX;
                float topX2 = REF_X_TOP[c + 1] * scaleX + offsetX;
                float botX1 = REF_X_BOT[c] * scaleX + offsetX;
                float botX2 = REF_X_BOT[c + 1] * scaleX + offsetX;
                x1 = Math.round(topX1 + t * (botX1 - topX1));
                x2 = Math.round(topX2 + t * (botX2 - topX2));
            }
            if (x2 <= x1 || x1 < 0 || x2 >= imgW) return null;

            return new RectF(x1, y1, x2, y2);
        }
    }

    public static class DetectionResult {
        public List<ParkingRecord> records;
        public List<DetectedMark> detectedMarks;
        public Bitmap annotatedBitmap;
        public TableGrid grid;
        public int totalMarkCount;
        public int personWithMarksCount;
        public boolean isDynamicDetected;
        public int detectedPage = 1;

        public DetectionResult(List<ParkingRecord> records, List<DetectedMark> detectedMarks, 
                               Bitmap annotatedBitmap, TableGrid grid, boolean isDynamicDetected, int detectedPage) {
            this.records = records;
            this.detectedMarks = detectedMarks;
            this.annotatedBitmap = annotatedBitmap;
            this.grid = grid;
            this.isDynamicDetected = isDynamicDetected;
            this.detectedPage = detectedPage;
            this.totalMarkCount = detectedMarks.size();
            
            int count = 0;
            for (ParkingRecord r : records) {
                if (r.hasAnyChecked()) {
                    count++;
                }
            }
            this.personWithMarksCount = count;
        }

        public DetectionResult(List<ParkingRecord> records, List<DetectedMark> detectedMarks, 
                               Bitmap annotatedBitmap, TableGrid grid, boolean isDynamicDetected) {
            this(records, detectedMarks, annotatedBitmap, grid, isDynamicDetected, 1);
        }
    }

    private static class MarkCandidate {
        final int r, c;
        final int strokeCount;
        final int maxCompArea, maxCompW, maxCompH;
        final int distTop, distBot;
        final RectF box;
        final float conf;

        MarkCandidate(int r, int c, int strokeCount, int maxCompArea, int maxCompW, int maxCompH,
                      int distTop, int distBot, RectF box, float conf) {
            this.r = r;
            this.c = c;
            this.strokeCount = strokeCount;
            this.maxCompArea = maxCompArea;
            this.maxCompW = maxCompW;
            this.maxCompH = maxCompH;
            this.distTop = distTop;
            this.distBot = distBot;
            this.box = box;
            this.conf = conf;
        }
    }

    // 基准水平线（40条横线，包含表头底线到表格底线，用于默认校准兜底）
    public static final int[] REF_H_LINES = {
        216, 231, 247, 264, 281, 297, 313, 328, 344, 360, 376, 392, 408, 424, 442, 
        459, 475, 491, 508, 523, 540, 556, 573, 590, 607, 624, 641, 658, 676, 693, 
        711, 729, 747, 765, 783, 801, 820, 838, 857, 875
    };

    // 梯形校正：顶部 15 条竖线与底部 15 条竖线位置（消除拍摄透视形变）
    public static final int[] REF_X_TOP = {
        202, 226, 249, 272, 296, 319, 342, 365, 388, 411, 434, 457, 480, 502, 525
    };
    public static final int[] REF_X_BOT = {
        203, 228, 251, 275, 299, 323, 347, 371, 395, 419, 444, 468, 493, 517, 541
    };

    public static final float REF_WIDTH = 576.0f;
    public static final float REF_HEIGHT = 1024.0f;

    public static DetectionResult detect(Bitmap inputBitmap, List<ParkingRecord> baseRoster, 
                                         float sensitivity, int offsetX, int offsetY, boolean showGrid) {
        return detect(inputBitmap, baseRoster, sensitivity, offsetX, offsetY, showGrid, true);
    }

    /**
     * 自动检测图像中的表格与打钩，集成自适应绿色表头定位与形态学网格线剔除算法
     */
    public static DetectionResult detect(Bitmap inputBitmap, List<ParkingRecord> baseRoster, 
                                         float sensitivity, int offsetX, int offsetY, 
                                         boolean showGrid, boolean showBadge) {
        if (inputBitmap == null) return null;

        int width = inputBitmap.getWidth();
        int height = inputBitmap.getHeight();

        float scaleX = (float) width / REF_WIDTH;
        float scaleY = (float) height / REF_HEIGHT;

        List<ParkingRecord> records = new ArrayList<>();
        if (baseRoster != null && !baseRoster.isEmpty()) {
            for (ParkingRecord r : baseRoster) {
                records.add(new ParkingRecord(r.getIndex(), r.getName(), r.getPlateNumber()));
            }
        } else {
            records = ShiftConstants.createDefaultRecords();
        }

        // 1. 提取灰度与统计绿色像素（寻找表格特征：绿色的星期表头）
        int[] pixels = new int[width * height];
        inputBitmap.getPixels(pixels, 0, width, 0, 0, width, height);
        int[][] gray = new int[height][width];
        int[][] pixelsR = new int[height][width];
        int[][] pixelsG = new int[height][width];
        int[][] pixelsB = new int[height][width];
        int[] rowGreenCount = new int[height];

        for (int y = 0; y < height; y++) {
            int rowOffset = y * width;
            for (int x = 0; x < width; x++) {
                int c = pixels[rowOffset + x];
                int r = (c >> 16) & 0xFF;
                int g = (c >> 8) & 0xFF;
                int b = c & 0xFF;
                pixelsR[y][x] = r;
                pixelsG[y][x] = g;
                pixelsB[y][x] = b;
                gray[y][x] = (int) (0.299 * r + 0.587 * g + 0.114 * b);

                int maxVal = Math.max(r, Math.max(g, b));
                int minVal = Math.min(r, Math.min(g, b));
                int diffVal = maxVal - minVal;
                boolean isGreen = (g > 65 && g > (int)(r * 1.12f) && g > (int)(b * 1.12f));
                if (isGreen) {
                    rowGreenCount[y]++;
                }
            }
        }

        // 2. 使用积分图快速计算高精度局部均值背景 (Symmetric Box Filter)
        int rad = Math.max(5, Math.round(7 * scaleX));
        long[][] intImg = computeIntegral(gray, width, height);
        int[][] blur = boxBlurWithIntegral(intImg, width, height, rad);

        // 3. 自适应二值化
        int deltaThresh = Math.max(6, Math.round(10.0f / sensitivity));
        boolean[][] binary = new boolean[height][width];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                binary[y][x] = (gray[y][x] < blur[y][x] - deltaThresh);
            }
        }

        // 4. 提取水平网格线（连续暗像素 >= 16），并进行形态学过滤标记
        int minHLineLen = Math.max(16, Math.round(20 * scaleX));
        boolean[][] isHLine = new boolean[height][width];
        for (int y = 0; y < height; y++) {
            int run = 0;
            int startX = 0;
            for (int x = 0; x < width; x++) {
                if (binary[y][x]) {
                    if (run == 0) startX = x;
                    run++;
                } else {
                    if (run >= minHLineLen) {
                        markHLine(isHLine, y, startX, x, height);
                    }
                    run = 0;
                }
            }
            if (run >= minHLineLen) {
                markHLine(isHLine, y, startX, width, height);
            }
        }

        // 5. 提取垂直网格线（连续暗像素 >= 12），并进行形态学过滤标记
        int minVLineLen = Math.max(8, Math.round(11 * scaleY));
        boolean[][] isVLine = new boolean[height][width];
        for (int x = 0; x < width; x++) {
            int run = 0;
            int startY = 0;
            for (int y = 0; y < height; y++) {
                if (binary[y][x]) {
                    if (run == 0) startY = y;
                    run++;
                } else {
                    if (run >= minVLineLen) {
                        markVLine(isVLine, x, startY, y, width);
                    }
                    run = 0;
                }
            }
            if (run >= minVLineLen) {
                markVLine(isVLine, x, startY, height, width);
            }
        }

        // 6. 纯笔迹提取掩膜（剔除水平线与垂直线，仅保留倾斜笔迹）
        boolean[][] cleanStrokes = new boolean[height][width];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                cleanStrokes[y][x] = binary[y][x] && !isHLine[y][x] && !isVLine[y][x];
            }
        }

        // 7. 动态寻找绿色表头 (星期一 ~ 星期日) 与数据行网格
        // 限制在顶部 38% 区域内搜索，彻底杜绝桌垫、阴影、衣物等底端杂色干扰
        int peakGreenY = 0;
        int maxGreen = 0;
        int searchMaxY = Math.round(height * 0.38f);
        for (int y = 0; y < searchMaxY; y++) {
            if (rowGreenCount[y] > maxGreen) {
                maxGreen = rowGreenCount[y];
                peakGreenY = y;
            }
        }

        boolean isTargetPage2 = (records.size() > 0 && records.get(0).getIndex() >= 40);
        TableGrid grid = new TableGrid();
        grid.scaleX = scaleX;
        grid.scaleY = scaleY;
        grid.isPage2 = isTargetPage2;

        boolean useDynamic = (maxGreen > width * 0.12f);
        if (useDynamic) {
            int greenTop = peakGreenY, greenBot = peakGreenY;
            int thresh = Math.round(maxGreen * 0.35f);
            int maxGreenThreshY = Math.max(35, Math.round(45 * scaleY));
            while (greenTop > 0 && rowGreenCount[greenTop - 1] > thresh && (peakGreenY - greenTop) < maxGreenThreshY) greenTop--;
            while (greenBot < height - 1 && rowGreenCount[greenBot + 1] > thresh && (greenBot - peakGreenY) < maxGreenThreshY) greenBot++;
            int estRowH = Math.max(10, greenBot - greenTop + 1);

            List<Integer> greenXs = new ArrayList<>();
            List<Integer> leftGreenYs = new ArrayList<>();
            List<Integer> rightGreenYs = new ArrayList<>();
            for (int y = greenTop; y <= greenBot; y++) {
                for (int x = 0; x < width; x++) {
                    int r = pixelsR[y][x], g = pixelsG[y][x], b = pixelsB[y][x];
                    boolean isGreen = (g > 65 && g > (int)(r * 1.12f) && g > (int)(b * 1.12f));
                    if (isGreen) {
                        greenXs.add(x);
                        if (x < width * 0.45f) leftGreenYs.add(y);
                        else if (x > width * 0.75f) rightGreenYs.add(y);
                    }
                }
            }
            if (!leftGreenYs.isEmpty() && !rightGreenYs.isEmpty()) {
                double avgLeft = leftGreenYs.stream().mapToInt(Integer::intValue).average().orElse(0.0);
                double avgRight = rightGreenYs.stream().mapToInt(Integer::intValue).average().orElse(0.0);
                grid.tilt = (float)(avgLeft - avgRight);
            }
            if (!greenXs.isEmpty()) {
                greenXs.sort(Integer::compareTo);
                grid.shiftsX1 = greenXs.get(0) - 1;
                grid.shiftsX2 = greenXs.get(greenXs.size() - 1);
                grid.colW = (grid.shiftsX2 - grid.shiftsX1) / 14.0f;

                int xl = Math.max(0, Math.round(grid.shiftsX1));
                int xr = Math.min(width - 1, Math.round(grid.shiftsX2));
                int shiftW = xr - xl;
                int minCov = Math.max(25, Math.round(shiftW * 0.08f));

                List<Integer> rawPeaks = new ArrayList<>();
                List<Integer> rawCovs = new ArrayList<>();
                for (int y = greenBot; y < height - 3; y++) {
                    int cov = 0;
                    for (int x = xl; x <= xr; x++) {
                        float t = (float)(x - xl) / shiftW;
                        int ny = Math.round(y - grid.tilt * t);
                        if (ny >= 0 && ny < height && isHLine[ny][x]) cov++;
                    }
                    if (cov >= minCov) {
                        boolean isPeak = true;
                        for (int dy = -2; dy <= 2; dy++) {
                            int ny_base = y + dy;
                            if (ny_base >= 0 && ny_base < height) {
                                int nCov = 0;
                                for (int x = xl; x <= xr; x++) {
                                    float t = (float)(x - xl) / shiftW;
                                    int ny = Math.round(ny_base - grid.tilt * t);
                                    if (ny >= 0 && ny < height && isHLine[ny][x]) nCov++;
                                }
                                if (nCov > cov) { isPeak = false; break; }
                            }
                        }
                        if (isPeak) {
                            if (rawPeaks.isEmpty() || (y - rawPeaks.get(rawPeaks.size() - 1)) >= 8) {
                                rawPeaks.add(y);
                                rawCovs.add(cov);
                            }
                        }
                    }
                }

                // 找到数据行起始线（越过白/夜表头）
                int startIdx = 0;
                for (int i = 0; i < rawPeaks.size(); i++) {
                    if (rawPeaks.get(i) - greenBot >= 8) {
                        startIdx = i;
                        break;
                    }
                }

                // 提取数据行候选线并统计行高间隔中位数
                List<Integer> dataPeaks = new ArrayList<>();
                List<Integer> dataCovs = new ArrayList<>();
                for (int i = startIdx; i < rawPeaks.size(); i++) {
                    dataPeaks.add(rawPeaks.get(i));
                    dataCovs.add(rawCovs.get(i));
                }

                List<Integer> diffList = new ArrayList<>();
                for (int i = 0; i < dataPeaks.size() - 1; i++) {
                    int d = dataPeaks.get(i + 1) - dataPeaks.get(i);
                    if (d >= 14 && d <= 32) diffList.add(d);
                }
                diffList.sort(Integer::compareTo);
                int medStep = diffList.isEmpty() ? estRowH : diffList.get(diffList.size() / 2);

                // 过滤间距过小（< 0.75 * medStep）的假峰，保留覆盖度更高的真线
                List<Integer> hPeaks = new ArrayList<>();
                List<Integer> fCovs = new ArrayList<>();
                for (int i = 0; i < dataPeaks.size(); i++) {
                    int y = dataPeaks.get(i);
                    int c = dataCovs.get(i);
                    if (hPeaks.isEmpty()) {
                        hPeaks.add(y);
                        fCovs.add(c);
                    } else {
                        int gap = y - hPeaks.get(hPeaks.size() - 1);
                        if (gap < Math.round(medStep * 0.75f)) {
                            if (c > fCovs.get(fCovs.size() - 1)) {
                                hPeaks.set(hPeaks.size() - 1, y);
                                fCovs.set(fCovs.size() - 1, c);
                            }
                        } else {
                            hPeaks.add(y);
                            fCovs.add(c);
                        }
                    }
                }

                for (int y : hPeaks) {
                    grid.hLines.add(y);
                }

                // 自动补齐缺失行（如折痕或微弱光线导致漏检，按中位数行高插值）
                if (grid.hLines.size() >= 3) {
                    List<Integer> diffs = new ArrayList<>();
                    for (int i = 0; i < grid.hLines.size() - 1; i++) {
                        diffs.add(grid.hLines.get(i + 1) - grid.hLines.get(i));
                    }
                    diffs.sort(Integer::compareTo);
                    int medH = diffs.get(diffs.size() / 2);
                    if (medH < 8) medH = estRowH;

                    List<Integer> repaired = new ArrayList<>();
                    repaired.add(grid.hLines.get(0));
                    for (int i = 0; i < grid.hLines.size() - 1; i++) {
                        int cur = grid.hLines.get(i);
                        int nxt = grid.hLines.get(i + 1);
                        int gap = nxt - cur;
                        if (gap > 1.6f * medH) {
                            int steps = Math.round((float) gap / medH);
                            float stepH = (float) gap / steps;
                            for (int s = 1; s < steps; s++) {
                                repaired.add(Math.round(cur + s * stepH));
                            }
                        }
                        repaired.add(nxt);
                    }
                    grid.hLines = repaired;
                }

                // 若拍摄被遮挡或底部较暗，自动按等高外推补齐至目标行数
                int neededLines = records.size() + 1;
                while (grid.hLines.size() < neededLines && grid.hLines.size() >= 2) {
                    int lastY = grid.hLines.get(grid.hLines.size() - 1);
                    int avgH = (lastY - grid.hLines.get(0)) / (grid.hLines.size() - 1);
                    if (avgH <= 5) avgH = estRowH;
                    int nextY = lastY + avgH;
                    if (nextY >= height - 2) break;
                    grid.hLines.add(nextY);
                }

                if (grid.hLines.size() >= 5) {
                    grid.isDynamic = true;
                } else if (useDynamic && estRowH >= 8) {
                    // 若横线检测较弱但绿色表头已准确定位，直接依据表头底边与行高动态生成行网格
                    grid.hLines.clear();
                    int startY = greenBot + Math.round(estRowH * 0.9f);
                    for (int i = 0; i < neededLines; i++) {
                        grid.hLines.add(startY + i * estRowH);
                    }
                    grid.isDynamic = true;
                }

                // 自适应网格竖线主动吸附与追踪 (Active Grid Line Snapping & Tracking)
                // 克服拍摄倾斜、纸张弯曲与透视梯形形变，精准锁定单元格真实物理黑线
                if (grid.isDynamic) {
                    int[] topVLines = new int[15];
                    int searchRangeTop = Math.max(6, Math.round(5.0f * scaleX));
                    for (int c = 0; c < 15; c++) {
                        int estX = Math.round(grid.shiftsX1 + c * grid.colW);
                        int bestX = estX;
                        long minSum = Long.MAX_VALUE;
                        for (int dx = -searchRangeTop; dx <= searchRangeTop; dx++) {
                            int cx = estX + dx;
                            if (cx >= 0 && cx < width) {
                                long sum = 0;
                                int startY = greenBot;
                                int endY = (grid.hLines.size() > 0) ? grid.hLines.get(0) : (greenBot + 15);
                                for (int y = startY; y < endY; y++) {
                                    sum += gray[y][cx];
                                }
                                if (sum < minSum) {
                                    minSum = sum;
                                    bestX = cx;
                                }
                            }
                        }
                        topVLines[c] = bestX;
                    }

                    int totalRows = Math.min(records.size(), grid.hLines.size() - 1);
                    int[] curVLines = topVLines.clone();
                    grid.rowVLines = new ArrayList<>();

                    for (int r = 0; r < totalRows; r++) {
                        int y1 = grid.hLines.get(r);
                        int y2 = grid.hLines.get(r + 1);
                        int[] nextVLines = new int[15];

                        // 宽范围自适应吸附与移动：适应手持拍摄梯形透视形变，防止竖线漂移切断笔画
                        int searchRange = Math.max(5, Math.round(4.0f * scaleX));
                        int maxMove = Math.max(3, Math.round(2.5f * scaleX));

                        for (int c = 0; c < 15; c++) {
                            int estX = curVLines[c];
                            int bestX = estX;
                            long minSum = Long.MAX_VALUE;
                            for (int dx = -searchRange; dx <= searchRange; dx++) {
                                int cx = estX + dx;
                                if (cx >= 0 && cx < width) {
                                    long sum = 0;
                                    int cy1 = Math.max(0, Math.min(height - 1, Math.min(y2 - 1, y1 + 2)));
                                    int cy2 = Math.max(cy1 + 1, Math.min(height, y2 - 2));
                                    for (int y = cy1; y < cy2; y++) {
                                        sum += gray[y][cx];
                                    }
                                    if (sum < minSum) {
                                        minSum = sum;
                                        bestX = cx;
                                    }
                                }
                            }
                            int move = Math.max(-maxMove, Math.min(maxMove, bestX - estX));
                            int candidateX = estX + move;
                            if (c > 0 && candidateX <= nextVLines[c - 1]) {
                                candidateX = nextVLines[c - 1] + Math.round(grid.colW * 0.8f);
                            }
                            nextVLines[c] = candidateX;
                        }
                        curVLines = nextVLines;
                        grid.rowVLines.add(curVLines.clone());
                    }
                }
            }
        }

        // 自动识别拍摄页码：第二页无顶部大标题，绿色表头位置明显偏上 (< 14% 图像高度)
        int detectedPage = 1;
        if (useDynamic && ((float) peakGreenY / height) <= 0.14f) {
            detectedPage = 2;
        }

        // 8. 逐单元格执行笔画连通域分析
        List<DetectedMark> detectedList = new ArrayList<>();
        int maxRow = records.size();
        if (grid.isDynamic && grid.hLines.size() >= 2) {
            maxRow = Math.min(records.size(), grid.hLines.size() - 1);
        }

        float colRefW = grid.isDynamic ? grid.colW : (23.0f * scaleX);
        float rowRefH = (grid.isDynamic && grid.hLines.size() >= 2) 
            ? ((float)(grid.hLines.get(grid.hLines.size() - 1) - grid.hLines.get(0)) / (grid.hLines.size() - 1))
            : (16.0f * scaleY);

        int minStrokesWeekday = Math.max(10, Math.round(10.0f * (colRefW / 23.0f) / sensitivity));
        int minStrokesWeekend = minStrokesWeekday;
        int minAreaWeekday = Math.max(6, Math.round(6.0f * (colRefW / 23.0f) / sensitivity));
        int minAreaWeekend = minAreaWeekday;
        int minW = 4;
        int minH = Math.max(4, Math.round(3.5f * (rowRefH / 16.0f)));
        int maxStrokes = Math.round(90.0f * (colRefW / 23.0f) * (rowRefH / 16.0f) * sensitivity);
        int effectiveMinContrast = calibratedMinContrast;

        MarkCandidate[][] candidates = new MarkCandidate[maxRow][14];

        // 第一轮：候选打钩提取
        for (int r = 0; r < maxRow; r++) {
            for (int c = 0; c < 14; c++) {
                RectF cellRect = grid.getCellRect(r, c, offsetX, offsetY, width, height);
                if (cellRect == null) continue;

                int x1 = Math.round(cellRect.left);
                int y1 = Math.round(cellRect.top);
                int x2 = Math.round(cellRect.right);
                int y2 = Math.round(cellRect.bottom);

                // 添加内缩边距，避开单元格边框线毛刺；底部保留 1 像素避免裁切打钩勾尖
                int insetX = Math.max(2, Math.round(1.5f * scaleX));
                int insetY = Math.max(2, Math.round(1.5f * scaleY));
                int insetYBot = Math.max(1, Math.round(1.0f * scaleY));
                int cy1 = Math.min(y2 - 1, y1 + insetY);
                int cy2 = Math.max(cy1 + 1, y2 - insetYBot);
                int cx1 = Math.min(x2 - 1, x1 + insetX);
                int cx2 = Math.max(cx1 + 1, x2 - insetX);

                int cellW = cx2 - cx1;
                int cellH = cy2 - cy1;
                if (cellW <= 0 || cellH <= 0) continue;

                // 统计单元格内部纯手写笔画像素
                int strokeCount = 0;
                for (int py = cy1; py < cy2; py++) {
                    for (int px = cx1; px < cx2; px++) {
                        if (cleanStrokes[py][px]) strokeCount++;
                    }
                }

                boolean isWeekend = (c >= 10);
                int reqStrokes = isWeekend ? minStrokesWeekend : minStrokesWeekday;
                int reqArea = isWeekend ? minAreaWeekend : minAreaWeekday;

                pass1: if (strokeCount >= reqStrokes && strokeCount <= maxStrokes) {

                // 连通域连贯性分析 (Connected Component Analysis)
                boolean[][] visited = new boolean[cellH][cellW];
                int[] qx = new int[cellH * cellW];
                int[] qy = new int[cellH * cellW];
                int maxCompArea = 0;
                int secondCompArea = 0;
                int bestMinX = 0, bestMaxX = 0, bestMinY = 0, bestMaxY = 0;
                int secondMinX = 0, secondMaxX = 0, secondMinY = 0, secondMaxY = 0;

                for (int py = 0; py < cellH; py++) {
                    for (int px = 0; px < cellW; px++) {
                        if (cleanStrokes[cy1 + py][cx1 + px] && !visited[py][px]) {
                            int head = 0, tail = 0;
                            qx[tail] = px;
                            qy[tail] = py;
                            tail++;
                            visited[py][px] = true;

                            int cMinX = px, cMaxX = px;
                            int cMinY = py, cMaxY = py;
                            int compArea = 0;

                            while (head < tail) {
                                int cx = qx[head];
                                int cy = qy[head];
                                head++;
                                compArea++;

                                if (cx < cMinX) cMinX = cx;
                                if (cx > cMaxX) cMaxX = cx;
                                if (cy < cMinY) cMinY = cy;
                                if (cy > cMaxY) cMaxY = cy;

                                for (int dy = -1; dy <= 1; dy++) {
                                    for (int dx = -1; dx <= 1; dx++) {
                                        if (dx == 0 && dy == 0) continue;
                                        int nx = cx + dx;
                                        int ny = cy + dy;
                                        if (nx >= 0 && nx < cellW && ny >= 0 && ny < cellH) {
                                            if (cleanStrokes[cy1 + ny][cx1 + nx] && !visited[ny][nx]) {
                                                visited[ny][nx] = true;
                                                qx[tail] = nx;
                                                qy[tail] = ny;
                                                tail++;
                                            }
                                        }
                                    }
                                }
                            }

                            int curW = cMaxX - cMinX + 1;
                            int curH = cMaxY - cMinY + 1;
                            // 忽略水平或垂直残留网格线，并从笔画总数中扣除干扰线像素
                            if (curW >= Math.round(cellW * 0.65f) && curH <= Math.max(3, Math.round(4.0f * scaleY))) {
                                strokeCount = Math.max(0, strokeCount - compArea);
                                continue;
                            }
                            if (curH >= Math.round(cellH * 0.70f) && curW <= Math.max(3, Math.round(3.0f * scaleX))) {
                                strokeCount = Math.max(0, strokeCount - compArea);
                                continue;
                            }
                            if (compArea > maxCompArea) {
                                secondCompArea = maxCompArea;
                                secondMinX = bestMinX;
                                secondMaxX = bestMaxX;
                                secondMinY = bestMinY;
                                secondMaxY = bestMaxY;
                                maxCompArea = compArea;
                                bestMinX = cMinX;
                                bestMaxX = cMaxX;
                                bestMinY = cMinY;
                                bestMaxY = cMaxY;
                            } else if (compArea > secondCompArea) {
                                secondCompArea = compArea;
                                secondMinX = cMinX;
                                secondMaxX = cMaxX;
                                secondMinY = cMinY;
                                secondMaxY = cMaxY;
                            }
                        }
                    }
                }

                // 排除浅灰擦除残留 (Eraser residue suppression)
                int minGrayInComp = 255;
                for (int py = cy1; py < cy2; py++) {
                    for (int px = cx1; px < cx2; px++) {
                        if (cleanStrokes[py][px] && gray[py][px] < minGrayInComp) {
                            minGrayInComp = gray[py][px];
                        }
                    }
                }
                int cellMidY = (cy1 + cy2) / 2;
                int cellMidX = (cx1 + cx2) / 2;
                int localBg = blur[cellMidY][cellMidX];
                int markContrast = localBg - minGrayInComp;

                // 双笔划断笔合并 (Two-stroke checkmark union)
                // 增强对较淡铅笔/细笔迹的自适应兼容(结合自校准对比度)，且至少一笔具备水平跨度(max(w1, w2) >= 4)，排除双并列细竖条与擦除残留
                int w1 = bestMaxX - bestMinX + 1;
                int w2 = secondMaxX - secondMinX + 1;
                int h1 = bestMaxY - bestMinY + 1;
                int h2 = secondMaxY - secondMinY + 1;
                int overlapY = Math.max(0, Math.min(bestMaxY, secondMaxY) - Math.max(bestMinY, secondMinY) + 1);
                boolean isParallelBars = (w1 <= 3 && w2 <= 3) || (overlapY >= 4 && (float)overlapY / Math.max(1, Math.min(h1, h2)) >= 0.70f);

                if (markContrast >= effectiveMinContrast && !isParallelBars && Math.max(w1, w2) >= 4 &&
                    secondCompArea >= 6 && (maxCompArea + secondCompArea) >= Math.round(strokeCount * 0.60f)) {
                    int combinedW = Math.max(bestMaxX, secondMaxX) - Math.min(bestMinX, secondMinX) + 1;
                    int combinedH = Math.max(bestMaxY, secondMaxY) - Math.min(bestMinY, secondMinY) + 1;
                    if (combinedW >= 4 && combinedH >= minH) {
                        bestMinX = Math.min(bestMinX, secondMinX);
                        bestMaxX = Math.max(bestMaxX, secondMaxX);
                        bestMinY = Math.min(bestMinY, secondMinY);
                        bestMaxY = Math.max(bestMaxY, secondMaxY);
                        maxCompArea = maxCompArea + secondCompArea;
                    }
                }

                int curW = bestMaxX - bestMinX + 1;
                int curH = bestMaxY - bestMinY + 1;

                if (markContrast < 16 || (maxCompArea < 6 && strokeCount < 14) || (maxCompArea <= 7 && markContrast < effectiveMinContrast)) {
                    break pass1;
                }

                // 污渍与非打钩杂斑过滤 (Stain & smudge suppression)
                // 1. 宽度不足4像素，不具备打钩勾角跨度
                if (curW < 4) {
                    break pass1;
                }

                // 2. 散乱污渍：笔画点数较多但最大连通块占比过低 (散斑噪声)
                // 真实打钩笔画常因提笔断为两笔或三笔，主连通块占比达 38% 或前两大连通块合占 60% 即属于有效笔画
                float minAreaRatio = (strokeCount >= 35) ? 0.38f : ((maxCompArea >= 9 && markContrast >= 45) ? 0.35f : 0.45f);
                int minAreaFloor = 8;
                boolean isScatteredNoise = (strokeCount >= 20 && (maxCompArea < strokeCount * minAreaRatio || maxCompArea < minAreaFloor));
                if (isScatteredNoise) {
                    if ((maxCompArea + secondCompArea) >= Math.round(strokeCount * 0.60f) && maxCompArea >= 15 && curW >= 4 && curH >= minH) {
                        // 双笔划有效打钩豁免
                    } else {
                        break pass1;
                    }
                }

                // 3. 极窄垂直划痕/残留线 (宽度仅4像素但高度过高，且连通块面积过小)
                if (curW <= 4 && curH >= 9 && maxCompArea < 15) {
                    break pass1;
                }

                if (maxCompArea >= reqArea && curH >= minH) {
                    float conf = Math.min(1.0f, (maxCompArea / 30.0f));
                    RectF box = new RectF(x1, y1, x2, y2);
                    int dTop = bestMinY;
                    int dBot = cellH - 1 - bestMaxY;
                    candidates[r][c] = new MarkCandidate(r, c, strokeCount, maxCompArea, curW, curH, dTop, dBot, box, conf);
                }

                }

                // 浅淡 / 细笔迹手写勾补偿检测 (Faint checkmark recovery)
                // 针对笔迹纤细或用笔较轻的真实打钩（如刘文虎J6），在排除网格线后结合单元格内实际纸张背景进行二次连通域验证
                if (candidates[r][c] == null) {
                    int[] hist = new int[256];
                    for (int py = cy1; py < cy2; py++) {
                        for (int px = cx1; px < cx2; px++) {
                            hist[gray[py][px]]++;
                        }
                    }
                    int totalCellPix = cellH * cellW;
                    int countP = 0, p85 = 255;
                    for (int g = 0; g < 256; g++) {
                        countP += hist[g];
                        if (countP >= Math.round(totalCellPix * 0.85f)) {
                            p85 = g;
                            break;
                        }
                    }
                    int minG = 255;
                    for (int g = 0; g < 256; g++) {
                        if (hist[g] > 0) { minG = g; break; }
                    }
                    int cellContrast = p85 - minG;

                    // 具备有效对比度（自适应兼容浅铅笔灰，结合校准对比度），并避开上下边框渗漏
                    if (cellContrast >= effectiveMinContrast) {
                        int fThresh = p85 - 24;
                        boolean[][] fMask = new boolean[cellH][cellW];
                        for (int py = 0; py < cellH; py++) {
                            for (int px = 0; px < cellW; px++) {
                                int gy = cy1 + py;
                                int gx = cx1 + px;
                                if (gray[gy][gx] < fThresh && !isHLine[gy][gx] && !isVLine[gy][gx]) {
                                    fMask[py][px] = true;
                                }
                            }
                        }

                        // 连通块分析
                        boolean[][] fVis = new boolean[cellH][cellW];
                        int[] fQx = new int[cellH * cellW];
                        int[] fQy = new int[cellH * cellW];
                        int fMaxArea = 0, fBestW = 0, fBestH = 0;
                        int fBestMinY = 0, fBestMaxY = 0;

                        for (int py = 0; py < cellH; py++) {
                            for (int px = 0; px < cellW; px++) {
                                if (fMask[py][px] && !fVis[py][px]) {
                                    int fHead = 0, fTail = 0;
                                    fQx[fTail] = px; fQy[fTail] = py; fTail++;
                                    fVis[py][px] = true;
                                    int cMinX = px, cMaxX = px, cMinY = py, cMaxY = py;
                                    int cArea = 0;
                                    while (fHead < fTail) {
                                        int fx = fQx[fHead]; int fy = fQy[fHead]; fHead++;
                                        cArea++;
                                        if (fx < cMinX) cMinX = fx;
                                        if (fx > cMaxX) cMaxX = fx;
                                        if (fy < cMinY) cMinY = fy;
                                        if (fy > cMaxY) cMaxY = fy;
                                        for (int dy = -1; dy <= 1; dy++) {
                                            for (int dx = -1; dx <= 1; dx++) {
                                                if (dx == 0 && dy == 0) continue;
                                                int nx = fx + dx; int ny = fy + dy;
                                                if (nx >= 0 && nx < cellW && ny >= 0 && ny < cellH) {
                                                    if (fMask[ny][nx] && !fVis[ny][nx]) {
                                                        fVis[ny][nx] = true;
                                                        fQx[fTail] = nx; fQy[fTail] = ny; fTail++;
                                                    }
                                                }
                                            }
                                        }
                                    }
                                    int cW = cMaxX - cMinX + 1;
                                    int cH = cMaxY - cMinY + 1;
                                    // 忽略水平或垂直残留网格线与下划线
                                    if (cW >= Math.round(cellW * 0.65f) && cH <= Math.max(3, Math.round(4.0f * scaleY))) {
                                        continue;
                                    }
                                    if (cH >= Math.round(cellH * 0.70f) && cW <= Math.max(3, Math.round(3.0f * scaleX))) {
                                        continue;
                                    }
                                    if (cArea > fMaxArea) {
                                        fMaxArea = cArea;
                                        fBestW = cW;
                                        fBestH = cH;
                                        fBestMinY = cMinY;
                                        fBestMaxY = cMaxY;
                                    }
                                }
                            }
                        }

                        int fDTop = fBestMinY;
                        int fDBot = cellH - 1 - fBestMaxY;
                        // 真实勾特征：具备一定宽高跨度与点数，且不贴顶线贴底线（非边框渗漏）
                        boolean isFaintCheck = (fMaxArea >= 10 && fBestW >= 6 && fBestH >= 5 && ((fDTop >= 1 && fDBot >= 1) || (fDTop >= 4 && fMaxArea <= 36)) && (fMaxArea <= 36 || ((float)fMaxArea / (fBestW * fBestH) <= 0.42f)));
                        if (isFaintCheck) {
                            RectF box = new RectF(x1, y1, x2, y2);
                            candidates[r][c] = new MarkCandidate(r, c, fMaxArea, fMaxArea, fBestW, fBestH, fDTop, fDBot, box, 0.85f);
                        }
                    }
                }
            }
        }

        // 第二轮：跨行边界渗漏抑制与真实性确认 (Border-crossing & Bleed Suppression)
        for (int r = 0; r < maxRow; r++) {
            for (int c = 0; c < 14; c++) {
                MarkCandidate cand = candidates[r][c];
                if (cand == null) continue;

                boolean isBleed = false;

                // 检查是否为上一行大笔画穿出下边界渗漏到本格
                if (r > 0 && candidates[r - 1][c] != null) {
                    MarkCandidate above = candidates[r - 1][c];
                    if (cand.distTop <= 1 && above.distBot <= 1 && above.strokeCount >= 25 && 
                        (cand.strokeCount < 20 || cand.strokeCount < above.strokeCount * 0.60f)) {
                        isBleed = true;
                    }
                }

                // 检查是否为下一行大笔画穿出上边界渗漏到本格
                if (r + 1 < maxRow && candidates[r + 1][c] != null) {
                    MarkCandidate below = candidates[r + 1][c];
                    if (cand.distBot <= 1 && below.distTop <= 1 && below.strokeCount >= 25 && 
                        (cand.strokeCount < 20 || cand.strokeCount < below.strokeCount * 0.60f)) {
                        isBleed = true;
                    }
                }

                if (!isBleed) {
                    ParkingRecord record = records.get(r);
                    record.setShiftChecked(c, true);
                    record.setConfidence(c, cand.conf);

                    String excelCell = record.getExcelCell(c);
                    String shiftName = ShiftConstants.SHIFT_NAMES[c];
                    detectedList.add(new DetectedMark(r, c, record.getName(), record.getPlateNumber(), 
                                                     excelCell, shiftName, cand.box, cand.conf));
                }
            }
        }

        Bitmap annotated = renderOverlay(inputBitmap, records, grid, showGrid, showBadge, offsetX, offsetY);
        return new DetectionResult(records, detectedList, annotated, grid, grid.isDynamic, detectedPage);
    }

    private static void markHLine(boolean[][] isHLine, int y, int startX, int endX, int height) {
        if (y >= 0 && y < height) {
            for (int px = startX; px < endX; px++) {
                isHLine[y][px] = true;
            }
        }
    }

    private static void markVLine(boolean[][] isVLine, int x, int startY, int endY, int width) {
        if (x >= 0 && x < width) {
            for (int py = startY; py < endY; py++) {
                isVLine[py][x] = true;
            }
        }
    }

    private static long[][] computeIntegral(int[][] src, int w, int h) {
        long[][] intImg = new long[h + 1][w + 1];
        for (int y = 0; y < h; y++) {
            long rowSum = 0;
            for (int x = 0; x < w; x++) {
                rowSum += src[y][x];
                intImg[y + 1][x + 1] = intImg[y][x + 1] + rowSum;
            }
        }
        return intImg;
    }

    private static int[][] boxBlurWithIntegral(long[][] intImg, int w, int h, int rad) {
        int[][] blur = new int[h][w];
        for (int y = 0; y < h; y++) {
            int y1 = Math.max(0, y - rad);
            int y2 = Math.min(h, y + rad + 1);
            for (int x = 0; x < w; x++) {
                int x1 = Math.max(0, x - rad);
                int x2 = Math.min(w, x + rad + 1);
                long area = (long) (y2 - y1) * (x2 - x1);
                long sum = intImg[y2][x2] - intImg[y1][x2] - intImg[y2][x1] + intImg[y1][x1];
                blur[y][x] = (int) (sum / area);
            }
        }
        return blur;
    }

    public static Bitmap renderOverlay(Bitmap baseBitmap, List<ParkingRecord> records, 
                                        TableGrid grid, boolean showGrid, int offsetX, int offsetY) {
        return renderOverlay(baseBitmap, records, grid, showGrid, true, offsetX, offsetY);
    }

    /**
     * 根据当前记录状态与网格快速重绘叠加图，支持只画边框（隐藏标号）或画边框+标号
     */
    public static Bitmap renderOverlay(Bitmap baseBitmap, List<ParkingRecord> records, 
                                        TableGrid grid, boolean showGrid, boolean showBadge, int offsetX, int offsetY) {
        if (baseBitmap == null) return null;

        int width = baseBitmap.getWidth();
        int height = baseBitmap.getHeight();
        if (grid == null) {
            grid = new TableGrid();
            grid.scaleX = (float) width / REF_WIDTH;
            grid.scaleY = (float) height / REF_HEIGHT;
        }

        Bitmap out = baseBitmap.copy(Bitmap.Config.ARGB_8888, true);
        Canvas canvas = new Canvas(out);

        Paint boxPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        boxPaint.setStyle(Paint.Style.STROKE);
        boxPaint.setColor(Color.parseColor("#00E676")); // 高亮鲜明亮绿
        boxPaint.setStrokeWidth(Math.max(1.8f, 2.0f * grid.scaleX));

        Paint textBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textBgPaint.setStyle(Paint.Style.FILL);
        textBgPaint.setColor(Color.parseColor("#C81B5E20")); // 78% 半透明护眼深绿，保护下层笔锋清晰可见

        Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(Math.max(9.5f, 10.5f * grid.scaleX));
        textPaint.setFakeBoldText(true);

        Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        gridPaint.setStyle(Paint.Style.STROKE);
        gridPaint.setColor(Color.parseColor("#60FFD600")); // 半透明黄色辅助网格
        gridPaint.setStrokeWidth(1.0f);

        int maxRow = (records != null) ? records.size() : 0;
        if (grid.isDynamic && grid.hLines.size() >= 2) {
            maxRow = Math.min(maxRow, grid.hLines.size() - 1);
        }

        for (int r = 0; r < maxRow; r++) {
            ParkingRecord record = records.get(r);

            for (int c = 0; c < 14; c++) {
                RectF cellRect = grid.getCellRect(r, c, offsetX, offsetY, width, height);
                if (cellRect == null) continue;

                if (showGrid) {
                    canvas.drawRect(cellRect, gridPaint);
                }

                if (record.isShiftChecked(c)) {
                    canvas.drawRoundRect(cellRect, 2, 2, boxPaint);
                    if (showBadge) {
                        String badge = record.getExcelCell(c);
                        float textW = textPaint.measureText(badge);
                        float cellW = cellRect.width();
                        float maxBadgeW = Math.max(12, cellW - 2);
                        float pillW = Math.min(textW + 4, maxBadgeW);
                        float pillH = textPaint.getTextSize() + 2;

                        RectF bg = new RectF(cellRect.left + 1, cellRect.top + 1, 
                                             cellRect.left + 1 + pillW, 
                                             cellRect.top + 1 + pillH);
                        canvas.drawRoundRect(bg, 2, 2, textBgPaint);
                        canvas.drawText(badge, cellRect.left + 2, 
                                        cellRect.top + textPaint.getTextSize() + 1, textPaint);
                    }
                }
            }
        }

        return out;
    }

    /**
     * 快速判定所输入图像属于第 1 页还是第 2 页
     */
    public static int determinePage(Bitmap bitmap) {
        if (bitmap == null) return 1;
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int sampleH = Math.min(height, Math.round(height * 0.38f));
        if (sampleH <= 0 || width <= 0) return 1;

        int[] pixels = new int[width * sampleH];
        bitmap.getPixels(pixels, 0, width, 0, 0, width, sampleH);

        int maxGreen = 0;
        int peakGreenY = 0;

        for (int y = 0; y < sampleH; y++) {
            int rowOffset = y * width;
            int count = 0;
            for (int x = 0; x < width; x++) {
                int c = pixels[rowOffset + x];
                int r = (c >> 16) & 0xFF;
                int g = (c >> 8) & 0xFF;
                int b = c & 0xFF;
                boolean isGreen = (g > 65 && g > (int)(r * 1.12f) && g > (int)(b * 1.12f));
                if (isGreen) count++;
            }
            if (count > maxGreen) {
                maxGreen = count;
                peakGreenY = y;
            }
        }

        if (maxGreen > width * 0.12f && ((float) peakGreenY / height) <= 0.14f) {
            return 2;
        }
        return 1;
    }

    public static Bitmap renderOverlay(Bitmap baseBitmap, List<ParkingRecord> records, 
                                        boolean showGrid, int offsetX, int offsetY) {
        return renderOverlay(baseBitmap, records, null, showGrid, offsetX, offsetY);
    }

    /**
     * 将点击的图像像素坐标转换为对应的行号和班次列号
     */
    public static int[] getCellAtPoint(float touchX, float touchY, int imgW, int imgH, 
                                       TableGrid grid, int offsetX, int offsetY, int rowCount) {
        if (grid == null) {
            grid = new TableGrid();
            grid.scaleX = (float) imgW / REF_WIDTH;
            grid.scaleY = (float) imgH / REF_HEIGHT;
        }

        int maxR = rowCount;
        if (grid.isDynamic && grid.hLines.size() >= 2) {
            maxR = Math.min(rowCount, grid.hLines.size() - 1);
        }

        for (int r = 0; r < maxR; r++) {
            for (int c = 0; c < 14; c++) {
                RectF cellRect = grid.getCellRect(r, c, offsetX, offsetY, imgW, imgH);
                if (cellRect != null && cellRect.contains(touchX, touchY)) {
                    return new int[]{r, c};
                }
            }
        }
        return null;
    }

    public static int[] getCellAtPoint(float touchX, float touchY, int imgW, int imgH, int offsetX, int offsetY) {
        return getCellAtPoint(touchX, touchY, imgW, imgH, null, offsetX, offsetY, 39);
    }
}
