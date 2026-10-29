package com.parking.checkscanner;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.util.Log;
import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 手写《废料登记表》识别：
 * 1. 在照片上找出表格的横线与竖线 (允许倾斜与透视收窄，每条线单独拟合斜率)；
 * 2. 用印刷体表头 (日期 / 回仓部门 / ...) 的位置确定 8 列，表头下方的横线逐行切分；
 * 3. 抹掉表格线后逐格切出手写内容，用 PaddleOCR 识别模型 (两个模型合用) 读 (ML Kit 读不懂潦草手写)，再按列规整：
 *    日期 / 重量 / 时间取数字，部门 / 物品 / 地点 / 人员按固定类别清单就近纠正，读不准的格子标为需核对。
 */
public class WasteSheetScanner {

    private static final String TAG = "WasteSheetScanner";

    // 照片长边上限：手写字需要足够像素才认得出
    public static final int MAX_IMAGE_DIM = 2048;

    // 表头关键字 (按列顺序)；「回仓」「跟进」「人员」在两列里都有，用各列独有的字区分
    private static final String[][] HEADER_KEYS = {
        {"日期", "期"}, {"部门"}, {"物品", "名称", "规格"}, {"重量", "Kg", "kg"},
        {"地点"}, {"跟进人"}, {"时间"}, {"保安"}
    };

    // 表头没认全时，按纸质表各列中心在表宽上的大致位置补齐
    private static final float[] DEFAULT_COL_CENTERS = {
        0.054f, 0.174f, 0.342f, 0.49f, 0.606f, 0.716f, 0.828f, 0.936f
    };

    /** 一条近似直线：横线为 y = pos + slope * (x - origin)，竖线为 x = pos + slope * (y - origin) */
    public static class Line {
        public final float pos;
        public final float slope;
        public final float origin;
        final int votes;

        Line(float pos, float slope, float origin, int votes) {
            this.pos = pos;
            this.slope = slope;
            this.origin = origin;
            this.votes = votes;
        }

        public float at(float t) {
            return pos + slope * (t - origin);
        }
    }

    /** 表格结构：rowLines 为数据区各行的上下边线 (行数 + 1 条)，colLines 为 8 列的左右边线 (9 条) */
    public static class Grid {
        public final List<Line> rowLines = new ArrayList<>();
        public final List<Line> colLines = new ArrayList<>();

        public int rowCount() {
            return Math.max(0, rowLines.size() - 1);
        }

        /** 点 (x, y) 所在的数据行，不在表内返回 -1 */
        public int rowAt(float x, float y) {
            for (int r = 0; r < rowCount(); r++) {
                if (y >= rowLines.get(r).at(x) && y < rowLines.get(r + 1).at(x)) return r;
            }
            return -1;
        }
    }

    public static class ScanResult {
        public Grid grid;
        public List<WasteRecord> records = new ArrayList<>();
        public String message; // 失败原因
    }

    /**
     * 识别一张废料登记表照片 (需在后台线程调用)。handwriting 为手写识别器 (可多个模型合用)，由调用方创建并在多张照片间复用
     */
    public static ScanResult scan(Bitmap bitmap, WasteCategories categories, List<PaddleTextRecognizer> handwriting) {
        ScanResult result = new ScanResult();
        int w = bitmap.getWidth();
        int h = bitmap.getHeight();

        // 1. 整图 ML Kit OCR 只用来找印刷体表头
        List<Glyph> glyphs = recognizePrinted(bitmap);
        if (glyphs == null) {
            result.message = "文字识别失败，请重试";
            return result;
        }

        // 2. 表格线
        int[] background = new int[w * h];
        boolean[] dark = binarize(bitmap, background);
        List<Line> hLines = findLines(dark, w, h, true);
        List<Line> vLines = findLines(dark, w, h, false);
        Log.i(TAG, "横线 " + hLines.size() + " 条，竖线 " + vLines.size() + " 条，整图文字 " + glyphs.size() + " 个");

        Grid grid = buildGrid(glyphs, hLines, vLines, w, h);
        if (grid == null || grid.rowCount() == 0) {
            result.message = "没找到废料登记表的表头，请把整张表 (含「日期 / 回仓部门…」表头) 拍进画面后重试";
            return result;
        }
        result.grid = grid;

        // 3. 抹掉表格线 (否则会被读成 1、| 等干扰字符)，再找出跨行的大括号并抹掉
        //    (括号笔画同样会被读成乱码)，然后逐格切出手写内容识别；没有墨迹的格子跳过
        boolean[] ink = dark.clone();
        int[] cleanPx = eraseLines(bitmap, dark, ink, background, hLines, vLines, grid);
        List<Brace> braces = findBraces(ink, w, h, grid);
        eraseBraces(cleanPx, ink, background, w, grid, braces);
        Bitmap clean = Bitmap.createBitmap(cleanPx, w, h, Bitmap.Config.ARGB_8888);
        long t0 = System.currentTimeMillis();
        String[][] raw = new String[grid.rowCount()][WasteRecord.COL_COUNT];
        float[][] conf = new float[grid.rowCount()][WasteRecord.COL_COUNT];
        try {
            for (int r = 0; r < grid.rowCount(); r++) {
                for (int c = 0; c < WasteRecord.COL_COUNT; c++) {
                    PaddleTextRecognizer.Result res = readCell(handwriting, categories, clean, grid, r, c);
                    raw[r][c] = res.text;
                    conf[r][c] = res.confidence;
                }
            }
            for (Brace b : braces) readBraceText(handwriting, categories, clean, grid, b, raw);
        } finally {
            clean.recycle();
        }
        Log.i(TAG, "逐格手写识别用时 " + (System.currentTimeMillis() - t0) + "ms");
        int[][] braceOf = applyBraces(raw, conf, braces);
        parseRows(raw, conf, braceOf, categories, result);
        return result;
    }

    // 数字列 (重量) 识别置信度低于此值标为需核对
    private static final float SURE_DIGITS = 0.75f;

    private static void parseRows(String[][] raw, float[][] conf, int[][] braceOf, WasteCategories categories,
                                  ScanResult result) {
        int month = consensusMonth(raw);
        String prevDate = "";
        WasteRecord prev = null;
        for (int r = 0; r < raw.length; r++) {
            String[] cells = raw[r];
            boolean any = false;
            for (int c = 0; c < WasteRecord.COL_COUNT; c++) {
                if (c != WasteRecord.COL_DATE && !cells[c].isEmpty()) any = true;
            }
            if (!any) continue; // 没写字的空行 (只有日期格有零星笔画的也算)

            WasteRecord rec = new WasteRecord();
            rec.photoRow = r;
            for (int c = 0; c < WasteRecord.COL_COUNT; c++) {
                rec.setRaw(c, cells[c]);
                rec.braceGroup[c] = braceOf[r][c];
            }

            String date = parseDate(cells[WasteRecord.COL_DATE], month);
            if (date != null) {
                rec.set(WasteRecord.COL_DATE, date, false);
                prevDate = date;
            } else {
                // 读不出时沿用上一行日期 (同一天常连续多行)
                rec.set(WasteRecord.COL_DATE, prevDate, true);
            }

            String weight = parseNumber(cells[WasteRecord.COL_WEIGHT]);
            boolean weightClean = cells[WasteRecord.COL_WEIGHT].matches("[1-9]\\d*(\\.\\d+)?");
            rec.set(WasteRecord.COL_WEIGHT, weight, weight.isEmpty() || weight.length() > 5
                || !weightClean || conf[r][WasteRecord.COL_WEIGHT] < SURE_DIGITS);

            String time = parseTime(cells[WasteRecord.COL_TIME]);
            rec.set(WasteRecord.COL_TIME, time != null ? time : cells[WasteRecord.COL_TIME],
                time == null && !cells[WasteRecord.COL_TIME].isEmpty());

            for (int c = 0; c < WasteRecord.COL_COUNT; c++) {
                if (!WasteRecord.isCategoryColumn(c)) continue;
                WasteCategories.Match m = categories.match(c, cells[c]);
                boolean required = c == WasteRecord.COL_DEPT || c == WasteRecord.COL_ITEM || c == WasteRecord.COL_LOCATION;
                boolean repeats = c == WasteRecord.COL_DEPT || c == WasteRecord.COL_LOCATION || c == WasteRecord.COL_GUARD;
                boolean samePrev = prev != null && repeats && !prev.get(c).isEmpty();
                if (m.matched || !samePrev) {
                    // 人名列只是备查：对上了清单里的人就不再标黄，免得满屏黄格盖过要紧的列；
                    // 部门 / 地点勉强对上、但与上一行相同时也算可信
                    boolean check = required ? m.needsCheck || m.value.isEmpty() : m.needsCheck && !m.matched;
                    if (samePrev && m.value.equals(prev.get(c))) check = false;
                    rec.set(c, m.value, check);
                } else {
                    // 部门 / 地点 / 保安常与上一行相同：认不出时先填上一行的值 (部门 / 地点标为需核对)
                    rec.set(c, prev.get(c), required);
                }
            }
            Log.d(TAG, "第" + (r + 1) + "行 原文=" + String.join(" | ", cells) + " -> " + rec.summary()
                + " 重量置信度=" + conf[r][WasteRecord.COL_WEIGHT]);
            result.records.add(rec);
            prev = rec;
        }
        flagDatesOutOfOrder(result.records);
        fixTimesByOrder(result.records);
    }

    /**
     * 跟进时间按登记顺序递增 (换天时才会倒退)。格子左边没抹净的竖线常被读成开头多一个 1 (8:54 → 18:54)，
     * 这类时间可能是本身也可能要减 10 小时：整张表一起取能让时间最顺 (倒退最少) 的组合，改动过的标为需核对。
     * 不按日期分组：日期本身也可能读错，而换天造成的倒退对每种组合都一样
     */
    private static void fixTimesByOrder(List<WasteRecord> records) {
        List<WasteRecord> rows = new ArrayList<>();
        List<int[]> options = new ArrayList<>(); // 每行可选的分钟数：[原值] 或 [原值, 减 10 小时]
        for (WasteRecord rec : records) {
            int m = toMinutes(rec.get(WasteRecord.COL_TIME));
            if (m < 0) continue;
            rows.add(rec);
            options.add(m >= 600 && m < 1200 ? new int[] {m, m - 600} : new int[] {m});
        }
        int n = rows.size();
        if (n < 2) return;
        // 动态规划：cost[k][o] = 前 k 行、第 k 行取第 o 个选项时的最小代价 (见 stepCost，每次改动另记 0.1)
        float[][] cost = new float[n][2];
        int[][] from = new int[n][2];
        for (int o = 0; o < 2; o++) cost[0][o] = o < options.get(0).length ? o * 0.1f : Float.MAX_VALUE;
        for (int k = 1; k < n; k++) {
            for (int o = 0; o < 2; o++) {
                cost[k][o] = Float.MAX_VALUE;
                if (o >= options.get(k).length) continue;
                for (int p = 0; p < options.get(k - 1).length; p++) {
                    float c = cost[k - 1][p] + stepCost(options.get(k - 1)[p], options.get(k)[o]) + o * 0.1f;
                    if (c < cost[k][o]) {
                        cost[k][o] = c;
                        from[k][o] = p;
                    }
                }
            }
        }
        int o = cost[n - 1][1] < cost[n - 1][0] ? 1 : 0;
        for (int k = n - 1; k >= 0; k--) {
            if (o == 1) {
                int m = options.get(k)[1];
                rows.get(k).set(WasteRecord.COL_TIME, (m / 60) + ":" + String.format("%02d", m % 60), true);
            }
            o = from[k][o];
        }
    }

    /** 相邻两行时间的代价：倒退记 1 (换天或读错)；往后跳 4 小时以上记 0.5 (同一张表里少见) */
    private static float stepCost(int prevMinutes, int minutes) {
        if (minutes < prevMinutes) return 1;
        return minutes - prevMinutes > 240 ? 0.5f : 0;
    }

    /** 日期按登记顺序不减：与前后行顺序对不上、又不和任何一个相邻行相同的，标为需核对 */
    private static void flagDatesOutOfOrder(List<WasteRecord> records) {
        for (int i = 0; i < records.size(); i++) {
            WasteRecord rec = records.get(i);
            if (rec.get(WasteRecord.COL_DATE).isEmpty() || rec.isUncertain(WasteRecord.COL_DATE)) continue;
            int d = dayOf(rec.get(WasteRecord.COL_DATE));
            int before = i > 0 ? dayOf(records.get(i - 1).get(WasteRecord.COL_DATE)) : -1;
            int after = i + 1 < records.size() ? dayOf(records.get(i + 1).get(WasteRecord.COL_DATE)) : -1;
            boolean outOfOrder = (before > 0 && d < before) || (after > 0 && d > after);
            if (outOfOrder && d != before && d != after) rec.set(WasteRecord.COL_DATE, rec.get(WasteRecord.COL_DATE), true);
        }
    }

    private static int dayOf(String date) {
        try {
            return Integer.parseInt(date.substring(date.indexOf('.') + 1));
        } catch (Exception e) {
            return 0;
        }
    }

    private static int toMinutes(String time) {
        int colon = time.indexOf(':');
        if (colon <= 0) return -1;
        try {
            return Integer.parseInt(time.substring(0, colon)) * 60 + Integer.parseInt(time.substring(colon + 1));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    // ===== OCR =====

    private static class Glyph {
        final String text;
        final float cx;
        final float cy;

        Glyph(String text, Rect box) {
            this.text = text;
            this.cx = box.exactCenterX();
            this.cy = box.exactCenterY();
        }
    }

    /** ML Kit 整图 OCR (印刷体表头用)，返回逐字的文字与位置；失败返回 null */
    private static List<Glyph> recognizePrinted(Bitmap bitmap) {
        TextRecognizer recognizer = TextRecognition.getClient(new ChineseTextRecognizerOptions.Builder().build());
        try {
            Text text = Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, 0)), 20, TimeUnit.SECONDS);
            List<Glyph> glyphs = new ArrayList<>();
            for (Text.TextBlock block : text.getTextBlocks()) {
                for (Text.Line line : block.getLines()) {
                    for (Text.Element el : line.getElements()) {
                        List<Text.Symbol> symbols = el.getSymbols();
                        if (symbols != null && !symbols.isEmpty()) {
                            for (Text.Symbol sym : symbols) {
                                if (sym.getBoundingBox() != null) glyphs.add(new Glyph(sym.getText(), sym.getBoundingBox()));
                            }
                        } else if (el.getBoundingBox() != null) {
                            glyphs.add(new Glyph(el.getText(), el.getBoundingBox()));
                        }
                    }
                }
            }
            return glyphs;
        } catch (Exception e) {
            Log.e(TAG, "OCR 异常: " + e.getMessage(), e);
            return null;
        } finally {
            try {
                recognizer.close();
            } catch (Exception ignored) {}
        }
    }

    // 格子里墨迹像素占比低于此值视为空格
    private static final float MIN_INK_RATIO = 0.006f;

    private static final PaddleTextRecognizer.Result EMPTY = new PaddleTextRecognizer.Result("", 0);

    /**
     * 切出第 r 行第 c 列的格子 (外接矩形，左右各内缩一点，避开没抹干净的竖线——残留竖线会被读成 1)
     * 做手写识别；空格子或识别失败返回空结果
     */
    private static PaddleTextRecognizer.Result readCell(List<PaddleTextRecognizer> handwriting, WasteCategories categories,
                                                        Bitmap clean, Grid grid, int r, int c) {
        Bitmap cell = cropCell(clean, grid, r, c);
        if (cell == null) return EMPTY;
        try {
            if (inkRatio(cell) < MIN_INK_RATIO) return EMPTY;
            PaddleTextRecognizer.Result res = recognizeBest(handwriting, categories, c, cell);
            String text = res.text.trim();
            // 只有标点符号 (如 _ . -) 的是笔画残渣
            if (!text.matches(".*[\\p{L}\\p{N}].*")) return EMPTY;
            // 只读出一个符号且没把握的多半是残留线头 / 污点
            if (text.length() <= 1 && res.confidence < 0.7f && !text.matches("\\d")) return EMPTY;
            return new PaddleTextRecognizer.Result(text, res.confidence);
        } finally {
            cell.recycle();
        }
    }

    /**
     * 各模型分别识别，取更可信的一个：类别列 (部门 / 物品 / 地点 / 人员) 取与清单最像的，一样像时取置信度高的；
     * 其余列 (日期 / 重量 / 时间) 取置信度高的。某个模型出错时跳过它
     */
    private static PaddleTextRecognizer.Result recognizeBest(List<PaddleTextRecognizer> handwriting, WasteCategories categories,
                                                             int col, Bitmap image) {
        PaddleTextRecognizer.Result best = EMPTY;
        float bestScore = -1;
        for (PaddleTextRecognizer model : handwriting) {
            PaddleTextRecognizer.Result res;
            try {
                res = model.recognize(image);
            } catch (Exception e) {
                Log.e(TAG, "手写识别异常: " + e.getMessage(), e);
                continue;
            }
            if (res.text.trim().isEmpty()) continue;
            float score = WasteRecord.isCategoryColumn(col)
                ? categories.match(col, res.text).score * 10 + res.confidence
                : res.confidence;
            if (score > bestScore) {
                bestScore = score;
                best = res;
            }
        }
        return best;
    }

    /** 格子 (r, c) 的外接矩形，左右各内缩约 0.12 行高 (避开没抹干净的竖线)；太小返回 null */
    private static Bitmap cropCell(Bitmap clean, Grid grid, int r, int c) {
        float[] tl = intersect(grid.rowLines.get(r), grid.colLines.get(c));
        float[] tr = intersect(grid.rowLines.get(r), grid.colLines.get(c + 1));
        float[] bl = intersect(grid.rowLines.get(r + 1), grid.colLines.get(c));
        float[] br = intersect(grid.rowLines.get(r + 1), grid.colLines.get(c + 1));
        int y1 = Math.max(0, Math.round(Math.min(tl[1], tr[1])));
        int y2 = Math.min(clean.getHeight(), Math.round(Math.max(bl[1], br[1])));
        int inset = Math.round((y2 - y1) * 0.12f);
        int x1 = Math.max(0, Math.round(Math.max(tl[0], bl[0])) + inset);
        int x2 = Math.min(clean.getWidth(), Math.round(Math.min(tr[0], br[0])) - inset);
        if (x2 - x1 < 6 || y2 - y1 < 6) return null;
        return Bitmap.createBitmap(clean, x1, y1, x2 - x1, y2 - y1);
    }

    /**
     * 括号那一栏的字常竖着一格写一个 (如名字)，单个字单独识别很不准：把写了字的格子按从上到下的顺序
     * 横着拼成一行再识别，结果记在 b.text
     */
    private static void readBraceText(List<PaddleTextRecognizer> handwriting, WasteCategories categories, Bitmap clean,
                                      Grid grid, Brace b, String[][] raw) {
        List<Bitmap> parts = new ArrayList<>();
        for (int r = b.firstRow; r <= b.lastRow; r++) {
            if (raw[r][b.target].isEmpty()) continue;
            Bitmap cell = cropCell(clean, grid, r, b.target);
            if (cell != null) parts.add(cell);
        }
        if (parts.size() < 2) {
            for (Bitmap p : parts) p.recycle();
            return; // 只有一格时逐格识别的结果就是整段
        }
        int height = 0;
        for (Bitmap p : parts) height = Math.max(height, p.getHeight());
        int gap = height / 4;
        int width = 0;
        for (Bitmap p : parts) width += Math.round((float) p.getWidth() * height / p.getHeight()) + gap;
        Bitmap line = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(line);
        canvas.drawColor(parts.get(0).getPixel(0, 0));
        Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        int x = 0;
        for (Bitmap p : parts) {
            int pw = Math.round((float) p.getWidth() * height / p.getHeight());
            canvas.drawBitmap(p, null, new Rect(x, 0, x + pw, height), paint);
            x += pw + gap;
            p.recycle();
        }
        try {
            PaddleTextRecognizer.Result res = recognizeBest(handwriting, categories, b.target, line);
            if (!res.text.trim().isEmpty()) {
                b.text = res.text.trim();
                b.confidence = res.confidence;
            }
        } finally {
            line.recycle();
        }
    }

    /** 抹线后格子里的墨迹占比 (比格子平均亮度明显暗的像素) */
    private static float inkRatio(Bitmap cell) {
        int w = cell.getWidth();
        int h = cell.getHeight();
        int[] px = new int[w * h];
        cell.getPixels(px, 0, w, 0, 0, w, h);
        long sum = 0;
        int[] gray = new int[px.length];
        for (int i = 0; i < px.length; i++) {
            int p = px[i];
            gray[i] = (((p >> 16) & 0xFF) * 299 + ((p >> 8) & 0xFF) * 587 + (p & 0xFF) * 114) / 1000;
            sum += gray[i];
        }
        float mean = (float) sum / px.length;
        int ink = 0;
        for (int g : gray) if (g < mean * 0.7f) ink++;
        return (float) ink / px.length;
    }

    /** 平均行高 */
    private static float rowHeight(Grid grid) {
        return (grid.rowLines.get(grid.rowCount()).pos - grid.rowLines.get(0).pos) / grid.rowCount();
    }

    /** 抹表格线时线两侧各抹掉的宽度 */
    private static int lineTolerance(Grid grid) {
        return Math.max(2, Math.round(rowHeight(grid) * 0.1f));
    }

    /**
     * 表格线上 (线宽范围内) 的墨迹涂成周围纸面的亮度，返回抹线后的像素；
     * ink 为墨迹标记的副本，抹掉的像素同时清掉
     */
    private static int[] eraseLines(Bitmap bitmap, boolean[] dark, boolean[] ink, int[] background,
                                    List<Line> hLines, List<Line> vLines, Grid grid) {
        int w = bitmap.getWidth();
        int h = bitmap.getHeight();
        int[] px = new int[w * h];
        bitmap.getPixels(px, 0, w, 0, 0, w, h);
        int t = lineTolerance(grid);

        List<Line> horizontals = new ArrayList<>(hLines);
        horizontals.addAll(grid.rowLines);
        for (Line l : horizontals) {
            for (int x = 0; x < w; x++) {
                int yc = Math.round(l.at(x));
                for (int y = Math.max(0, yc - t); y <= Math.min(h - 1, yc + t); y++) {
                    int i = y * w + x;
                    if (dark[i]) {
                        px[i] = gray(background[i]);
                        ink[i] = false;
                    }
                }
            }
        }
        List<Line> verticals = new ArrayList<>(vLines);
        verticals.addAll(grid.colLines);
        for (Line l : verticals) {
            for (int y = 0; y < h; y++) {
                int xc = Math.round(l.at(y));
                for (int x = Math.max(0, xc - t); x <= Math.min(w - 1, xc + t); x++) {
                    int i = y * w + x;
                    if (dark[i]) {
                        px[i] = gray(background[i]);
                        ink[i] = false;
                    }
                }
            }
        }
        return px;
    }

    // ===== 跨行大括号 =====

    /**
     * 纸上用一个竖着的大括号括住几行、旁边只写一次 (如跟进人员一栏括住十几行，中间竖着写一个名字)，
     * 表示括住的各行这一栏都是这个值。firstRow ~ lastRow 为括住的行，xs[k] 为第 firstRow + k 行里括号笔画的横坐标
     */
    static class Brace {
        final int firstRow;
        final int lastRow;
        final float[] xs;
        int target = -1; // 括号说明的那一栏
        String text; // 该栏写的字连成一行识别的结果 (没识别出时为 null)
        float confidence;

        Brace(int firstRow, int lastRow, float[] xs) {
            this.firstRow = firstRow;
            this.lastRow = lastRow;
            this.xs = xs;
        }

        float midX() {
            float[] sorted = xs.clone();
            java.util.Arrays.sort(sorted);
            return sorted[sorted.length / 2];
        }
    }

    // 至少连续括住这么多行才算括号
    private static final int MIN_BRACE_ROWS = 3;

    /**
     * 从第 row 行位置 x 的竖笔往下找下一段：下一行 (或隔一行，括号笔画淡时某一行可能断了) 里位置相近、
     * 且笔画穿过中间横线的竖笔。返回 {行, 下标}，没有返回 null
     */
    private static int[] nextStroke(List<List<Float>> strokes, boolean[][] used, boolean[] ink, int w, int h,
                                    Grid grid, int row, float x, float maxShift, int t) {
        for (int next = row + 1; next <= row + 2 && next < strokes.size(); next++) {
            for (int j = 0; j < strokes.get(next).size(); j++) {
                float x2 = strokes.get(next).get(j);
                if (used[next][j] || Math.abs(x2 - x) > maxShift) continue;
                // 隔一行时只要求进入下一行的那条线被穿过
                if (crossesLine(ink, w, h, grid.rowLines.get(row + 1), next == row + 1 ? (x + x2) / 2 : x, t)) {
                    return new int[] {next, j};
                }
            }
        }
        return null;
    }

    /**
     * 找括号：抹线后，在每行里找几乎贯穿整行高度的竖笔，上下相邻两行的竖笔位置相近、
     * 且笔画穿过两行之间的横线 (线的上下两侧都有墨迹) 就连成一串，连满 MIN_BRACE_ROWS 行即为括号。
     * 括号两端弯钩所在的行竖笔不够长，只要笔画穿线连过去也算进来
     */
    private static List<Brace> findBraces(boolean[] ink, int w, int h, Grid grid) {
        int rows = grid.rowCount();
        float rowH = rowHeight(grid);
        int t = lineTolerance(grid);
        float maxShift = rowH * 0.35f;

        // 每行的竖笔位置
        List<List<Float>> strokes = new ArrayList<>();
        for (int r = 0; r < rows; r++) strokes.add(findRowStrokes(ink, w, h, grid, r, t));

        List<Brace> braces = new ArrayList<>();
        boolean[][] used = new boolean[rows][];
        for (int r = 0; r < rows; r++) used[r] = new boolean[strokes.get(r).size()];
        for (int r = 0; r < rows; r++) {
            for (int i = 0; i < strokes.get(r).size(); i++) {
                if (used[r][i]) continue;
                // xs 按行记 (中间漏掉一行时用前一行的位置补上)
                List<Float> chain = new ArrayList<>();
                chain.add(strokes.get(r).get(i));
                used[r][i] = true;
                int last = r;
                while (last + 1 < rows) {
                    float x = chain.get(chain.size() - 1);
                    int[] hit = nextStroke(strokes, used, ink, w, h, grid, last, x, maxShift, t);
                    if (hit == null) break;
                    int nextRow = hit[0];
                    float x2 = strokes.get(nextRow).get(hit[1]);
                    used[nextRow][hit[1]] = true;
                    for (int gap = last + 1; gap < nextRow; gap++) chain.add(x);
                    chain.add(x2);
                    last = nextRow;
                }
                if (chain.size() < MIN_BRACE_ROWS) continue;

                int first = r;
                // 两端弯钩：笔画穿过最外侧的横线连进上 / 下一行
                if (first > 0 && crossesLine(ink, w, h, grid.rowLines.get(first), chain.get(0), t)) {
                    chain.add(0, chain.get(0));
                    first--;
                }
                if (last + 1 < rows && crossesLine(ink, w, h, grid.rowLines.get(last + 1), chain.get(chain.size() - 1), t)) {
                    chain.add(chain.get(chain.size() - 1));
                    last++;
                }
                float[] xs = new float[chain.size()];
                for (int k = 0; k < xs.length; k++) xs[k] = chain.get(k);
                Brace b = new Brace(first, last, xs);
                if (isLineRemnant(b, grid)) continue;
                b.target = chooseTarget(b, ink, w, grid);
                Log.i(TAG, "竖笔串：第" + (first + 1) + "~" + (last + 1) + "行，x≈" + Math.round(b.midX())
                    + (b.target >= 0 ? "，是「" + WasteRecord.COLUMN_NAMES[b.target] + "」的括号" : "，旁边各列都写满，不是括号"));
                if (b.target < 0) continue;
                b = extendBrace(b, ink, w, h, grid, t);
                Log.i(TAG, "括号延伸后：第" + (b.firstRow + 1) + "~" + (b.lastRow + 1) + "行");
                b.target = chooseTarget(b, ink, w, grid);
                if (b.target >= 0) braces.add(b);
            }
        }
        return braces;
    }

    /**
     * 第 r 行 (抹线后、上下两条横线之间) 里像括号一段的笔画：一个独立的连通笔画，
     * 几乎从上线连到下线 (高度占行内 75% 以上)、窄 (宽不超过 0.4 行高) 且细 (墨迹量不超过高 × 0.2 行高)。
     * 手写字的竖笔一般连着别的笔画、或碰不到上下两条线。返回各笔画的横向中心
     */
    private static List<Float> findRowStrokes(boolean[] ink, int w, int h, Grid grid, int r, int t) {
        List<Float> result = new ArrayList<>();
        float rowH = rowHeight(grid);
        Line top = grid.rowLines.get(r);
        Line bottom = grid.rowLines.get(r + 1);
        Line left = grid.colLines.get(0);
        Line right = grid.colLines.get(WasteRecord.COL_COUNT);
        float yMid = (top.at(left.at(top.pos)) + bottom.at(left.at(bottom.pos))) / 2;
        int x1 = Math.max(0, Math.round(left.at(yMid)) - t);
        int x2 = Math.min(w - 1, Math.round(right.at(yMid)) + t);
        int y1 = Math.max(0, Math.round(Math.min(top.at(x1), top.at(x2))) + t + 1);
        int y2 = Math.min(h - 1, Math.round(Math.max(bottom.at(x1), bottom.at(x2))) - t - 1);
        if (y2 - y1 < 4 || x2 <= x1) return result;

        int bw = x2 - x1 + 1;
        int bh = y2 - y1 + 1;
        boolean[] seen = new boolean[bw * bh];
        int[] queue = new int[bw * bh];
        for (int sy = 0; sy < bh; sy++) {
            for (int sx = 0; sx < bw; sx++) {
                int si = sy * bw + sx;
                if (seen[si] || !inBand(ink, w, top, bottom, t, x1 + sx, y1 + sy)) continue;
                // 广度优先取出整个连通笔画 (8 邻接)，只在本行上下两线之间
                int head = 0;
                int tail = 0;
                queue[tail++] = si;
                seen[si] = true;
                int minX = sx, maxX = sx, minY = sy, maxY = sy, count = 0;
                while (head < tail) {
                    int cur = queue[head++];
                    int cx = cur % bw;
                    int cy = cur / bw;
                    count++;
                    minX = Math.min(minX, cx);
                    maxX = Math.max(maxX, cx);
                    minY = Math.min(minY, cy);
                    maxY = Math.max(maxY, cy);
                    for (int dy = -1; dy <= 1; dy++) {
                        for (int dx = -1; dx <= 1; dx++) {
                            int nx = cx + dx;
                            int ny = cy + dy;
                            if (nx < 0 || ny < 0 || nx >= bw || ny >= bh) continue;
                            int ni = ny * bw + nx;
                            if (seen[ni] || !inBand(ink, w, top, bottom, t, x1 + nx, y1 + ny)) continue;
                            seen[ni] = true;
                            queue[tail++] = ni;
                        }
                    }
                }
                // 本行实际行内高度按笔画所在位置算 (横线有倾斜)
                float xc = x1 + (minX + maxX) / 2f;
                float inner = bottom.at(xc) - top.at(xc) - 2 * t - 2;
                int height = maxY - minY + 1;
                int width = maxX - minX + 1;
                if (height >= inner * 0.75f && width <= rowH * 0.4f && count <= height * rowH * 0.2f) {
                    result.add(xc);
                }
            }
        }
        return result;
    }

    /** (x, y) 是墨迹且在横线 top、bottom 之间 (抹线范围以外) */
    private static boolean inBand(boolean[] ink, int w, Line top, Line bottom, int t, int x, int y) {
        return ink[y * w + x] && y > top.at(x) + t && y < bottom.at(x) - t;
    }

    /** 笔画是否在 x 处穿过横线 line：线上下两侧 (抹线范围外 4px 内) 都有墨迹 */
    private static boolean crossesLine(boolean[] ink, int w, int h, Line line, float x, int t) {
        int xc = Math.round(x);
        int yc = Math.round(line.at(x));
        return hasInk(ink, w, h, xc, yc - t - 4, yc - t - 1) && hasInk(ink, w, h, xc, yc + t + 1, yc + t + 4);
    }

    private static boolean hasInk(boolean[] ink, int w, int h, int xc, int y1, int y2) {
        for (int y = Math.max(0, y1); y <= Math.min(h - 1, y2); y++) {
            for (int x = Math.max(0, xc - 3); x <= Math.min(w - 1, xc + 3); x++) {
                if (ink[y * w + x]) return true;
            }
        }
        return false;
    }

    /**
     * 没抹干净的竖线残留也会连成一串「竖笔」：它紧贴某条列边线且笔直，括号则是弯的。
     * 各行竖笔离同一条列边线都在 0.2 行高内的视为残留
     */
    private static boolean isLineRemnant(Brace b, Grid grid) {
        float rowH = rowHeight(grid);
        for (Line col : grid.colLines) {
            boolean allNear = true;
            for (int k = 0; k < b.xs.length && allNear; k++) {
                int r = b.firstRow + k;
                float y = (grid.rowLines.get(r).at(b.xs[k]) + grid.rowLines.get(r + 1).at(b.xs[k])) / 2;
                if (Math.abs(b.xs[k] - col.at(y)) > rowH * 0.2f) allNear = false;
            }
            if (allNear) return true;
        }
        return false;
    }

    /** 抹掉括号笔画 (每行在括号位置左右 0.25 行高内的墨迹)，免得被当成字读 */
    private static void eraseBraces(int[] px, boolean[] ink, int[] background, int w, Grid grid, List<Brace> braces) {
        int h = px.length / w;
        int half = Math.round(rowHeight(grid) * 0.25f);
        for (Brace b : braces) {
            for (int k = 0; k < b.xs.length; k++) {
                int r = b.firstRow + k;
                int xc = Math.round(b.xs[k]);
                int y1 = Math.max(0, Math.round(grid.rowLines.get(r).at(xc)));
                int y2 = Math.min(h - 1, Math.round(grid.rowLines.get(r + 1).at(xc)));
                for (int y = y1; y <= y2; y++) {
                    for (int x = Math.max(0, xc - half); x <= Math.min(w - 1, xc + half); x++) {
                        int i = y * w + x;
                        if (ink[i]) {
                            px[i] = gray(background[i]);
                            ink[i] = false;
                        }
                    }
                }
            }
        }
    }

    /**
     * 括号笔画淡的地方 (尤其两端) 达不到 findRowStrokes 的标准：确认是括号后，再沿它的位置上下延伸，
     * 某行在括号位置附近有较短的竖笔 (高度过行内 35%)、且该栏这一格除括号外没有字，就算进来；
     * 中间最多容许连续两行看不出笔画
     */
    private static Brace extendBrace(Brace b, boolean[] ink, int w, int h, Grid grid, int t) {
        float shift = rowHeight(grid) * 0.35f;
        int half = Math.round(rowHeight(grid) * 0.25f);
        int first = b.firstRow;
        int last = b.lastRow;
        List<Float> xs = new ArrayList<>();
        for (float x : b.xs) xs.add(x);

        for (int dir : new int[] {-1, 1}) {
            float x = dir < 0 ? xs.get(0) : xs.get(xs.size() - 1);
            int r = (dir < 0 ? first : last) + dir;
            int misses = 0;
            List<Float> pending = new ArrayList<>(); // 尚未确认的空行 (之后找到笔画才补进去)
            while (r >= 0 && r < grid.rowCount() && misses <= 2) {
                float found = weakStrokeAt(ink, w, h, grid, r, x, shift, t);
                boolean emptyCell = cellInkRatio(ink, w, grid, r, b.target, Float.isNaN(found) ? x : found, half) < MIN_INK_RATIO;
                if (!emptyCell) break;
                if (Float.isNaN(found)) {
                    misses++;
                    pending.add(x);
                } else {
                    pending.add(found);
                    for (float px : pending) {
                        if (dir < 0) xs.add(0, px); else xs.add(px);
                    }
                    if (dir < 0) first -= pending.size(); else last += pending.size();
                    pending.clear();
                    misses = 0;
                    x = found;
                }
                r += dir;
            }
        }
        float[] arr = new float[xs.size()];
        for (int k = 0; k < arr.length; k++) arr[k] = xs.get(k);
        Brace out = new Brace(first, last, arr);
        out.target = b.target;
        return out;
    }

    /** 第 r 行在 x ± shift 内纵向覆盖最多的位置 (覆盖不到行内 35% 时返回 NaN) */
    private static float weakStrokeAt(boolean[] ink, int w, int h, Grid grid, int r, float x, float shift, int t) {
        Line top = grid.rowLines.get(r);
        Line bottom = grid.rowLines.get(r + 1);
        float bestX = Float.NaN;
        int best = 0;
        float inner = 0;
        for (int xx = Math.max(0, Math.round(x - shift)); xx <= Math.min(w - 1, Math.round(x + shift)); xx++) {
            int y1 = Math.max(0, Math.round(top.at(xx)) + t + 1);
            int y2 = Math.min(h - 1, Math.round(bottom.at(xx)) - t - 1);
            inner = y2 - y1 + 1;
            int covered = 0;
            for (int y = y1; y <= y2; y++) {
                if (ink[y * w + xx] || (xx > 0 && ink[y * w + xx - 1]) || (xx + 1 < w && ink[y * w + xx + 1])) covered++;
            }
            if (covered > best) {
                best = covered;
                bestX = xx;
            }
        }
        return best >= inner * 0.35f ? bestX : Float.NaN;
    }

    /**
     * 括号说明的是哪一栏：括号所在列及左右相邻列中，括住的行里只有少数 (至少一格、不超过一半) 格子有字的那一列
     * (其余列每行都写满；手写的 1 等竖笔所在列每格都有字，因此不会被当成括号)。按墨迹判断，不看识别结果，
     * 括号笔画本身不算。优先括号所在列，其次右边 (括号多写在内容左侧)，再次左边；都不符合返回 -1
     */
    private static int chooseTarget(Brace b, boolean[] ink, int w, Grid grid) {
        int span = b.lastRow - b.firstRow + 1;
        int mid = (b.firstRow + b.lastRow) / 2;
        float x = b.midX();
        float y = (grid.rowLines.get(mid).at(x) + grid.rowLines.get(mid + 1).at(x)) / 2;
        int c0 = -1;
        for (int c = 0; c < WasteRecord.COL_COUNT; c++) {
            if (x >= grid.colLines.get(c).at(y) && x < grid.colLines.get(c + 1).at(y)) c0 = c;
        }
        if (c0 < 0) return -1;
        int half = Math.round(rowHeight(grid) * 0.25f);
        for (int c : new int[] {c0, c0 + 1, c0 - 1}) {
            if (c < 0 || c >= WasteRecord.COL_COUNT) continue;
            int filled = 0;
            for (int r = b.firstRow; r <= b.lastRow; r++) {
                if (cellInkRatio(ink, w, grid, r, c, b.xs[r - b.firstRow], half) >= MIN_INK_RATIO) filled++;
            }
            if (filled > 0 && filled <= span / 2) return c;
        }
        return -1;
    }

    /** 格子 (r, c) 内墨迹占比，不计横坐标 excludeX 左右 half 内的像素 (括号笔画) */
    private static float cellInkRatio(boolean[] ink, int w, Grid grid, int r, int c, float excludeX, int half) {
        float[] tl = intersect(grid.rowLines.get(r), grid.colLines.get(c));
        float[] br = intersect(grid.rowLines.get(r + 1), grid.colLines.get(c + 1));
        int h = ink.length / w;
        int x1 = Math.max(0, Math.round(tl[0]));
        int x2 = Math.min(w - 1, Math.round(br[0]));
        int y1 = Math.max(0, Math.round(tl[1]));
        int y2 = Math.min(h - 1, Math.round(br[1]));
        if (x2 <= x1 || y2 <= y1) return 0;
        int count = 0;
        for (int y = y1; y <= y2; y++) {
            for (int x = x1; x <= x2; x++) {
                if (Math.abs(x - excludeX) > half && ink[y * w + x]) count++;
            }
        }
        return (float) count / ((x2 - x1 + 1) * (y2 - y1 + 1));
    }

    /**
     * 把括号括住的那一栏补齐：用该栏写的字 (拼成一行识别的结果，没有时按从上到下拼接逐格结果)
     * 填到括住的每一行，置信度取最低值。返回每格所属括号的编号 (从 1 起，0 为不在括号内)
     */
    private static int[][] applyBraces(String[][] raw, float[][] conf, List<Brace> braces) {
        int[][] braceOf = new int[raw.length][WasteRecord.COL_COUNT];
        for (int i = 0; i < braces.size(); i++) {
            Brace b = braces.get(i);
            int target = b.target;
            StringBuilder joined = new StringBuilder();
            float minConf = 1;
            for (int r = b.firstRow; r <= b.lastRow; r++) {
                if (raw[r][target].isEmpty()) continue;
                joined.append(raw[r][target]);
                minConf = Math.min(minConf, conf[r][target]);
            }
            String text = b.text != null ? b.text : joined.toString();
            if (b.text != null) minConf = b.confidence;
            if (text.isEmpty()) continue;
            for (int r = b.firstRow; r <= b.lastRow; r++) {
                raw[r][target] = text;
                conf[r][target] = minConf;
                braceOf[r][target] = i + 1;
            }
            Log.i(TAG, "括号第" + (b.firstRow + 1) + "~" + (b.lastRow + 1) + "行：「" + WasteRecord.COLUMN_NAMES[target]
                + "」一栏都填为 " + text + " (逐格拼接为 " + joined + ")");
        }
        return braceOf;
    }

    private static int gray(int g) {
        return Color.rgb(g, g, g);
    }

    // ===== 表格线检测 =====

    /** 局部自适应二值化：比周围平均亮度暗一定比例的像素记为墨迹；background 输出每个像素周围的平均亮度 */
    private static boolean[] binarize(Bitmap bitmap, int[] background) {
        int w = bitmap.getWidth();
        int h = bitmap.getHeight();
        int[] px = new int[w * h];
        bitmap.getPixels(px, 0, w, 0, 0, w, h);
        int[] gray = new int[w * h];
        for (int i = 0; i < px.length; i++) {
            int p = px[i];
            gray[i] = (((p >> 16) & 0xFF) * 299 + ((p >> 8) & 0xFF) * 587 + (p & 0xFF) * 114) / 1000;
        }
        // 255 x 像素数不超过 int 上限 (长边限制在 MAX_IMAGE_DIM 内)
        int[] integral = new int[(w + 1) * (h + 1)];
        for (int y = 0; y < h; y++) {
            int rowSum = 0;
            for (int x = 0; x < w; x++) {
                rowSum += gray[y * w + x];
                integral[(y + 1) * (w + 1) + x + 1] = integral[y * (w + 1) + x + 1] + rowSum;
            }
        }
        int rad = Math.max(8, Math.max(w, h) / 80);
        boolean[] dark = new boolean[w * h];
        for (int y = 0; y < h; y++) {
            int y1 = Math.max(0, y - rad);
            int y2 = Math.min(h, y + rad + 1);
            for (int x = 0; x < w; x++) {
                int x1 = Math.max(0, x - rad);
                int x2 = Math.min(w, x + rad + 1);
                int sum = integral[y2 * (w + 1) + x2] - integral[y1 * (w + 1) + x2]
                    - integral[y2 * (w + 1) + x1] + integral[y1 * (w + 1) + x1];
                float mean = (float) sum / ((x2 - x1) * (y2 - y1));
                int g = gray[y * w + x];
                background[y * w + x] = Math.round(mean);
                dark[y * w + x] = g < mean * 0.85f && mean - g > 12;
            }
        }
        return dark;
    }

    /**
     * 找横线 (horizontal) 或竖线：先留下沿该方向连续较长的墨迹 (表格线，手写笔画大多较短)，
     * 再按「截距 × 斜率」投票，每个截距取票数最高的斜率，局部峰值即为一条线
     */
    private static List<Line> findLines(boolean[] dark, int w, int h, boolean horizontal) {
        int along = horizontal ? w : h;   // 沿线方向长度
        int across = horizontal ? h : w;  // 垂直于线的方向长度
        int minRun = along / 22;
        int maxGap = 3;

        // 1. 沿线方向的长墨迹 (垂直方向容许 1px 抖动，兼容倾斜的细线)
        boolean[] mask = new boolean[w * h];
        for (int a = 0; a < across; a++) {
            int runStart = -1;
            int lastDark = -10;
            for (int t = 0; t <= along; t++) {
                boolean d = false;
                if (t < along) {
                    for (int da = -1; da <= 1 && !d; da++) {
                        int aa = a + da;
                        if (aa < 0 || aa >= across) continue;
                        d = dark[horizontal ? aa * w + t : t * w + aa];
                    }
                }
                if (d) {
                    if (runStart < 0 || t - lastDark > maxGap) runStart = t;
                    lastDark = t;
                } else if (runStart >= 0 && t - lastDark > maxGap) {
                    if (lastDark - runStart + 1 >= minRun) {
                        for (int k = runStart; k <= lastDark; k++) {
                            int idx = horizontal ? a * w + k : k * w + a;
                            if (dark[idx]) mask[idx] = true;
                        }
                    }
                    runStart = -1;
                }
            }
        }

        // 2. 截距 × 斜率投票 (截距取画面中线处的位置)
        float origin = along / 2f;
        int slopeSteps = 41;
        float maxSlope = 0.1f;
        int[][] acc = new int[slopeSteps][across];
        for (int a = 0; a < across; a++) {
            for (int t = 0; t < along; t++) {
                if (!mask[horizontal ? a * w + t : t * w + a]) continue;
                for (int s = 0; s < slopeSteps; s++) {
                    float slope = -maxSlope + 2 * maxSlope * s / (slopeSteps - 1);
                    int p = Math.round(a - slope * (t - origin));
                    if (p >= 0 && p < across) acc[s][p]++;
                }
            }
        }
        int[] best = new int[across];
        int[] bestSlope = new int[across];
        int globalMax = 0;
        for (int p = 0; p < across; p++) {
            for (int s = 0; s < slopeSteps; s++) {
                // 相邻截距合并计票，线宽 2~3px 时票数不被拆散
                int v = acc[s][p] + (p > 0 ? acc[s][p - 1] : 0) + (p + 1 < across ? acc[s][p + 1] : 0);
                if (v > best[p]) {
                    best[p] = v;
                    bestSlope[p] = s;
                }
            }
            globalMax = Math.max(globalMax, best[p]);
        }

        // 3. 局部峰值 (窗口内最大且不低于最强线的 30%)
        int win = Math.max(6, across / 120);
        List<Line> lines = new ArrayList<>();
        for (int p = 0; p < across; p++) {
            if (best[p] < globalMax * 0.3f) continue;
            boolean isPeak = true;
            for (int q = Math.max(0, p - win); q <= Math.min(across - 1, p + win) && isPeak; q++) {
                if (best[q] > best[p] || (best[q] == best[p] && q < p)) isPeak = false;
            }
            if (isPeak) {
                float slope = -maxSlope + 2 * maxSlope * bestSlope[p] / (slopeSteps - 1);
                lines.add(new Line(p, slope, origin, best[p]));
            }
        }
        return lines;
    }

    // ===== 表格结构 =====

    private static Grid buildGrid(List<Glyph> glyphs, List<Line> hLines, List<Line> vLines, int w, int h) {
        // 1. 找印刷体表头各列关键字的位置
        float[] headerX = new float[WasteRecord.COL_COUNT];
        float[] headerY = new float[WasteRecord.COL_COUNT];
        int found = 0;
        for (int c = 0; c < WasteRecord.COL_COUNT; c++) {
            headerX[c] = Float.NaN;
            float[] pos = findKeyword(glyphs, HEADER_KEYS[c]);
            if (pos != null) {
                headerX[c] = pos[0];
                headerY[c] = pos[1];
                found++;
            }
        }
        if (found < 3) {
            Log.w(TAG, "表头只认出 " + found + " 列");
            return null;
        }
        float headY = 0;
        for (int c = 0; c < WasteRecord.COL_COUNT; c++) if (!Float.isNaN(headerX[c])) headY += headerY[c];
        headY /= found;
        fillMissingCenters(headerX);

        // 2. 列边线：相邻两列表头之间最靠近中点的竖线，没有就用中点 (斜率取竖线中位数)
        float medianSlope = medianSlope(vLines);
        Grid grid = new Grid();
        for (int b = 0; b <= WasteRecord.COL_COUNT; b++) {
            float lo;
            float hi;
            float target;
            if (b == 0) {
                float colW = headerX[1] - headerX[0];
                lo = headerX[0] - colW;
                hi = headerX[0];
                target = headerX[0] - colW / 2;
            } else if (b == WasteRecord.COL_COUNT) {
                float colW = headerX[b - 1] - headerX[b - 2];
                lo = headerX[b - 1];
                hi = headerX[b - 1] + colW;
                target = headerX[b - 1] + colW / 2;
            } else {
                lo = headerX[b - 1];
                hi = headerX[b];
                target = (lo + hi) / 2;
            }
            Line bestLine = null;
            float bestDist = Float.MAX_VALUE;
            for (Line v : vLines) {
                float x = v.at(headY);
                if (x <= lo || x >= hi) continue;
                float d = Math.abs(x - target);
                if (d < bestDist) {
                    bestDist = d;
                    bestLine = v;
                }
            }
            grid.colLines.add(bestLine != null ? bestLine
                : new Line(target - medianSlope * (headY - h / 2f), medianSlope, h / 2f, 0));
        }

        // 3. 行边线：表头下方的横线，从表头底线起按行高连续取，行高突变 (表格结束) 时停止
        float midX = (grid.colLines.get(0).at(headY) + grid.colLines.get(WasteRecord.COL_COUNT).at(headY)) / 2;
        List<Line> below = new ArrayList<>();
        for (Line l : hLines) {
            if (l.at(midX) > headY) below.add(l);
        }
        Collections.sort(below, (a, b) -> Float.compare(a.at(midX), b.at(midX)));
        if (below.size() < 2) return null;

        List<Float> gaps = new ArrayList<>();
        for (int i = 1; i < below.size(); i++) gaps.add(below.get(i).at(midX) - below.get(i - 1).at(midX));
        List<Float> sortedGaps = new ArrayList<>(gaps);
        Collections.sort(sortedGaps);
        float rowH = sortedGaps.get(sortedGaps.size() / 2);

        grid.rowLines.add(below.get(0));
        for (int i = 1; i < below.size(); i++) {
            Line prev = grid.rowLines.get(grid.rowLines.size() - 1);
            Line cur = below.get(i);
            float gap = cur.at(midX) - prev.at(midX);
            if (gap < rowH * 0.55f) continue; // 手写笔画误检出的短线
            int n = Math.round(gap / rowH);
            if (n > 3) break; // 表格已结束
            // 漏检一两条线时按行高均分补上
            for (int k = 1; k < n; k++) {
                float f = (float) k / n;
                grid.rowLines.add(new Line(prev.pos + (cur.pos - prev.pos) * f,
                    prev.slope + (cur.slope - prev.slope) * f, prev.origin, 0));
            }
            grid.rowLines.add(cur);
        }
        Log.i(TAG, "表头 y=" + headY + "，行高 " + rowH + "，数据行 " + grid.rowCount());
        return grid;
    }

    /** 在 OCR 结果里按顺序找连续的关键字，返回其中心位置 */
    private static float[] findKeyword(List<Glyph> glyphs, String[] keys) {
        for (String key : keys) {
            for (int i = 0; i + key.length() <= glyphs.size(); i++) {
                StringBuilder sb = new StringBuilder();
                float sx = 0;
                float sy = 0;
                for (int k = 0; k < key.length() && sb.length() < key.length(); k++) {
                    Glyph g = glyphs.get(i + k);
                    sb.append(g.text);
                    sx += g.cx;
                    sy += g.cy;
                    if (sb.length() >= key.length()) {
                        if (sb.toString().startsWith(key)) return new float[] {sx / (k + 1), sy / (k + 1)};
                        break;
                    }
                }
            }
        }
        return null;
    }

    /** 没认出的表头列按纸质表各列的相对位置，从已认出的列线性推算 */
    private static void fillMissingCenters(float[] xs) {
        List<Integer> known = new ArrayList<>();
        for (int c = 0; c < xs.length; c++) if (!Float.isNaN(xs[c])) known.add(c);
        for (int c = 0; c < xs.length; c++) {
            if (!Float.isNaN(xs[c])) continue;
            // 取左右最近的两个已知列 (一侧没有时取同侧最近两个)
            int a = -1;
            int b = -1;
            for (int k : known) {
                if (k < c) a = k;
                if (k > c && b < 0) b = k;
            }
            if (a < 0) {
                a = known.get(0);
                b = known.get(1);
            } else if (b < 0) {
                b = a;
                a = known.get(known.size() - 2);
            }
            float scale = (xs[b] - xs[a]) / (DEFAULT_COL_CENTERS[b] - DEFAULT_COL_CENTERS[a]);
            xs[c] = xs[a] + (DEFAULT_COL_CENTERS[c] - DEFAULT_COL_CENTERS[a]) * scale;
        }
    }

    private static float medianSlope(List<Line> lines) {
        if (lines.isEmpty()) return 0;
        List<Float> s = new ArrayList<>();
        for (Line l : lines) s.add(l.slope);
        Collections.sort(s);
        return s.get(s.size() / 2);
    }

    // ===== 各列规整 =====

    private static final Pattern DATE_TWO = Pattern.compile("(\\d{1,2})\\D+(\\d{1,2})");
    private static final Pattern LAST_NUMBER = Pattern.compile("(\\d+)\\D*$");
    private static final Pattern TIME_TWO = Pattern.compile("(\\d{1,2})\\D+(\\d{2})");

    /** 手写数字常被读成形近字母，先统一换回数字 */
    static String digitsOnly(String s) {
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            switch (c) {
                case 'o': case 'O': case 'D': case '口': c = '0'; break;
                case 'l': case 'I': case 'i': case '|': case '!': case 'L': c = '1'; break;
                case 'z': case 'Z': c = '2'; break;
                case 's': case 'S': c = '5'; break;
                case 'b': case 'G': c = '6'; break;
                case 'B': c = '8'; break;
                case 'g': case 'q': c = '9'; break;
                default: break;
            }
            if (c >= 0xFF10 && c <= 0xFF19) c = (char) (c - 0xFEE0);
            sb.append(c);
        }
        return sb.toString();
    }

    /** 手写的「10」常被读成 b / h / 6 等单个字符，日期开头遇到时还原成 10 */
    private static String dateDigits(String raw) {
        String s = raw.trim().replaceFirst("^[^0-9A-Za-z]*[bhB6](?=\\D|$)", "10");
        return digitsOnly(s);
    }

    /** 整张表各行明确写出「月-日」的月份里出现最多的一个，都没有时取当前月份 */
    static int consensusMonth(String[][] raw) {
        int[] votes = new int[13];
        for (String[] row : raw) {
            Matcher m = DATE_TWO.matcher(dateDigits(row[WasteRecord.COL_DATE]));
            if (m.find()) {
                int month = Integer.parseInt(m.group(1));
                if (month >= 1 && month <= 12) votes[month]++;
            }
        }
        int best = java.time.LocalDate.now().getMonthValue();
        for (int i = 1; i <= 12; i++) if (votes[i] > votes[best]) best = i;
        return best;
    }

    /**
     * 日期写成「10.5」「10-7」等：只取最后的「日」，月份统一用整表的多数月份 (手写月份最容易读错)；
     * 读不出日返回 null
     */
    static String parseDate(String raw, int month) {
        String s = dateDigits(raw);
        // 只读出了开头的「10」(b)，日没读出来
        if (!s.equals(digitsOnly(raw.trim())) && !s.substring(2).matches(".*\\d.*")) return null;
        Matcher m = LAST_NUMBER.matcher(s);
        if (!m.find()) return null;
        String digits = m.group(1);
        int day = Integer.parseInt(digits);
        if (digits.length() >= 3 || digits.startsWith("0") || day > 31) {
            // 「105」「05」：分隔符没读出来，月份粘在前面，日取最后一位
            day = Integer.parseInt(digits.substring(digits.length() - 1));
        }
        if (day < 1 || day > 31) return null;
        return month + "." + day;
    }

    /** 重量只留数字 (去掉开头的 0) */
    static String parseNumber(String raw) {
        String digits = digitsOnly(raw).replaceAll("[^0-9.]", "");
        return digits.replaceAll("^[.0]+(?=\\d)|^\\.+|\\.+$", "");
    }

    /** 时间写成「8-54」「13:12」等：返回「H:MM」，读不出返回 null */
    static String parseTime(String raw) {
        String s = digitsOnly(raw);
        Matcher m = TIME_TWO.matcher(s);
        int hour = -1;
        int minute = -1;
        if (m.find()) {
            hour = Integer.parseInt(m.group(1));
            minute = Integer.parseInt(m.group(2));
        } else {
            String digits = s.replaceAll("\\D", "");
            if (digits.length() == 3 || digits.length() == 4) {
                hour = Integer.parseInt(digits.substring(0, digits.length() - 2));
                minute = Integer.parseInt(digits.substring(digits.length() - 2));
            }
        }
        if (hour < 0 || hour > 23 || minute < 0 || minute > 59) return null;
        return hour + ":" + String.format("%02d", minute);
    }

    // ===== 叠加图 =====

    /** 在照片上画出识别到的各行 (细框) 与需核对的格子 (黄底)，并在行左侧标出行号 */
    public static Bitmap renderOverlay(Bitmap base, Grid grid, List<WasteRecord> records) {
        Bitmap out = base.copy(Bitmap.Config.ARGB_8888, true);
        if (grid == null) return out;
        Canvas canvas = new Canvas(out);
        Paint frame = new Paint(Paint.ANTI_ALIAS_FLAG);
        frame.setStyle(Paint.Style.STROKE);
        frame.setStrokeWidth(Math.max(2f, base.getWidth() / 400f));
        frame.setColor(Color.rgb(0xFF, 0xB0, 0x00));
        Paint warn = new Paint(Paint.ANTI_ALIAS_FLAG);
        warn.setStyle(Paint.Style.FILL);
        warn.setColor(Color.argb(90, 0xFF, 0xD6, 0x0A));
        Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
        label.setColor(Color.rgb(0xC8, 0x10, 0x2E));
        label.setFakeBoldText(true);

        int last = WasteRecord.COL_COUNT;
        for (int i = 0; i < records.size(); i++) {
            WasteRecord rec = records.get(i);
            int r = rec.photoRow;
            if (r < 0 || r >= grid.rowCount()) continue;
            for (int c = 0; c < last; c++) {
                if (rec.isUncertain(c)) canvas.drawPath(cellPath(grid, r, c, c + 1), warn);
            }
            canvas.drawPath(cellPath(grid, r, 0, last), frame);

            float rowH = grid.rowLines.get(r + 1).at(0) - grid.rowLines.get(r).at(0);
            label.setTextSize(Math.max(14f, rowH * 0.5f));
            Line left = grid.colLines.get(0);
            float yMid = (grid.rowLines.get(r).at(left.at(0)) + grid.rowLines.get(r + 1).at(left.at(0))) / 2;
            float xLeft = left.at(yMid);
            String no = String.valueOf(i + 1);
            canvas.drawText(no, Math.max(2, xLeft - label.measureText(no) - 6), yMid + label.getTextSize() / 3, label);
        }
        return out;
    }

    /** 照片上第 r 行 (整行，上下各多留四分之一行) 的截图，供改行时对照；不在表内返回 null */
    public static Bitmap cropRow(Bitmap base, Grid grid, int r) {
        if (grid == null || r < 0 || r >= grid.rowCount()) return null;
        float[] tl = intersect(grid.rowLines.get(r), grid.colLines.get(0));
        float[] tr = intersect(grid.rowLines.get(r), grid.colLines.get(WasteRecord.COL_COUNT));
        float[] bl = intersect(grid.rowLines.get(r + 1), grid.colLines.get(0));
        float[] br = intersect(grid.rowLines.get(r + 1), grid.colLines.get(WasteRecord.COL_COUNT));
        float pad = (bl[1] - tl[1]) * 0.25f;
        int x1 = Math.max(0, Math.round(Math.min(tl[0], bl[0])));
        int x2 = Math.min(base.getWidth(), Math.round(Math.max(tr[0], br[0])));
        int y1 = Math.max(0, Math.round(Math.min(tl[1], tr[1]) - pad));
        int y2 = Math.min(base.getHeight(), Math.round(Math.max(bl[1], br[1]) + pad));
        if (x2 <= x1 || y2 <= y1) return null;
        return Bitmap.createBitmap(base, x1, y1, x2 - x1, y2 - y1);
    }

    private static Path cellPath(Grid grid, int r, int c1, int c2) {
        Line top = grid.rowLines.get(r);
        Line bottom = grid.rowLines.get(r + 1);
        Line left = grid.colLines.get(c1);
        Line right = grid.colLines.get(c2);
        float[] tl = intersect(top, left);
        float[] tr = intersect(top, right);
        float[] br = intersect(bottom, right);
        float[] bl = intersect(bottom, left);
        Path p = new Path();
        p.moveTo(tl[0], tl[1]);
        p.lineTo(tr[0], tr[1]);
        p.lineTo(br[0], br[1]);
        p.lineTo(bl[0], bl[1]);
        p.close();
        return p;
    }

    /** 横线与竖线的交点 (迭代两次即可收敛，两条线都接近水平 / 竖直) */
    private static float[] intersect(Line horizontal, Line vertical) {
        float y = horizontal.at(vertical.at(horizontal.pos));
        float x = vertical.at(y);
        y = horizontal.at(x);
        x = vertical.at(y);
        return new float[] {x, y};
    }
}
