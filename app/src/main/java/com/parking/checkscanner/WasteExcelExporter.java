package com.parking.checkscanner;

import android.content.Context;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 废料登记表导出：直接生成 .xlsx (不依赖模板)，
 * 第 1 张表与纸质表同列，末行重量合计；第 2 张表按物品名称、回仓部门汇总重量 (SUMIF 公式，附缓存值)。
 */
public class WasteExcelExporter {

    private static final String SHEET1 = "废料登记";
    private static final String SHEET2 = "汇总";

    // 样式序号，对应 styles.xml 的 cellXfs
    private static final int ST_TITLE = 1;
    private static final int ST_HEADER = 2;
    private static final int ST_TEXT = 3;
    private static final int ST_NUMBER = 4;
    private static final int ST_TOTAL = 5;
    private static final int ST_TOTAL_NUMBER = 6;

    public static File export(Context context, List<WasteRecord> records) throws Exception {
        File exportDir = new File(context.getExternalFilesDir(null), "exports");
        if (!exportDir.exists()) exportDir.mkdirs();
        File out = new File(exportDir, "废料登记表" + dateRange(records) + ".xlsx");

        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(out))) {
            put(zip, "[Content_Types].xml", CONTENT_TYPES);
            put(zip, "_rels/.rels", ROOT_RELS);
            put(zip, "xl/workbook.xml", WORKBOOK);
            put(zip, "xl/_rels/workbook.xml.rels", WORKBOOK_RELS);
            put(zip, "xl/styles.xml", STYLES);
            put(zip, "xl/worksheets/sheet1.xml", buildRegisterSheet(records));
            put(zip, "xl/worksheets/sheet2.xml", buildSummarySheet(records));
        }
        return out;
    }

    /** 文件名里的日期范围，如「10.5-10.7」；只有一天时为「10.7」 */
    static String dateRange(List<WasteRecord> records) {
        String first = null;
        String last = null;
        for (WasteRecord r : records) {
            String d = r.get(WasteRecord.COL_DATE);
            if (d.isEmpty()) continue;
            if (first == null || dateKey(d) < dateKey(first)) first = d;
            if (last == null || dateKey(d) > dateKey(last)) last = d;
        }
        if (first == null) return "";
        return first.equals(last) ? first : first + "-" + last;
    }

    private static int dateKey(String d) {
        try {
            String[] p = d.split("\\.");
            return Integer.parseInt(p[0]) * 100 + Integer.parseInt(p[1]);
        } catch (Exception e) {
            return 0;
        }
    }

    private static String buildRegisterSheet(List<WasteRecord> records) {
        int last = records.size() + 2; // 第 1 行标题，第 2 行表头
        StringBuilder sb = sheetStart(new double[] {8, 12, 22, 10, 12, 10, 10, 10}, 2);
        row(sb, 1, 26).append(str("A1", "废料登记表", ST_TITLE)).append("</row>");
        row(sb, 2, 20);
        for (int c = 0; c < WasteRecord.COL_COUNT; c++) {
            sb.append(str(ref(c, 2), WasteRecord.COLUMN_NAMES[c], ST_HEADER));
        }
        sb.append("</row>");

        double total = 0;
        for (int i = 0; i < records.size(); i++) {
            WasteRecord rec = records.get(i);
            int r = i + 3;
            row(sb, r, 18);
            for (int c = 0; c < WasteRecord.COL_COUNT; c++) {
                double w = c == WasteRecord.COL_WEIGHT ? rec.getWeight() : -1;
                if (w >= 0) {
                    sb.append(num(ref(c, r), w, ST_NUMBER));
                    total += w;
                } else {
                    sb.append(str(ref(c, r), rec.get(c), ST_TEXT));
                }
            }
            sb.append("</row>");
        }

        int totalRow = last + 1;
        row(sb, totalRow, 20).append(str(ref(0, totalRow), "合计", ST_TOTAL));
        for (int c = 1; c < WasteRecord.COL_COUNT; c++) {
            if (c == WasteRecord.COL_WEIGHT) {
                sb.append(formula(ref(c, totalRow), records.isEmpty() ? "0" : "SUM(D3:D" + last + ")", total, ST_TOTAL_NUMBER));
            } else {
                sb.append(str(ref(c, totalRow), "", ST_TOTAL));
            }
        }
        sb.append("</row>");
        return sheetEnd(sb, "<mergeCells count=\"1\"><mergeCell ref=\"A1:H1\"/></mergeCells>");
    }

    private static String buildSummarySheet(List<WasteRecord> records) {
        int dataLast = Math.max(3, records.size() + 2);
        Map<String, double[]> byItem = group(records, WasteRecord.COL_ITEM);
        Map<String, double[]> byDept = group(records, WasteRecord.COL_DEPT);

        StringBuilder sb = sheetStart(new double[] {22, 12, 8, 3, 14, 12, 8}, 1);
        row(sb, 1, 20)
            .append(str("A1", "物品名称/规格", ST_HEADER)).append(str("B1", "重量合计(Kg)", ST_HEADER))
            .append(str("C1", "笔数", ST_HEADER))
            .append(str("E1", "回仓部门", ST_HEADER)).append(str("F1", "重量合计(Kg)", ST_HEADER))
            .append(str("G1", "笔数", ST_HEADER))
            .append("</row>");

        List<String> items = new ArrayList<>(byItem.keySet());
        List<String> depts = new ArrayList<>(byDept.keySet());
        int rows = Math.max(items.size(), depts.size());
        for (int i = 0; i < rows; i++) {
            int r = i + 2;
            row(sb, r, 18);
            if (i < items.size()) appendGroupRow(sb, "A", "B", "C", r, items.get(i), byItem.get(items.get(i)), "C", dataLast);
            if (i < depts.size()) appendGroupRow(sb, "E", "F", "G", r, depts.get(i), byDept.get(depts.get(i)), "B", dataLast);
            sb.append("</row>");
        }
        return sheetEnd(sb, "");
    }

    private static void appendGroupRow(StringBuilder sb, String nameCol, String sumCol, String countCol, int r,
                                       String name, double[] agg, String keyCol, int dataLast) {
        String range = "'" + SHEET1 + "'!" + keyCol + "3:" + keyCol + dataLast;
        String weights = "'" + SHEET1 + "'!D3:D" + dataLast;
        sb.append(str(nameCol + r, name, ST_TEXT));
        sb.append(formula(sumCol + r, "SUMIF(" + range + "," + nameCol + r + "," + weights + ")", agg[0], ST_NUMBER));
        sb.append(formula(countCol + r, "COUNTIF(" + range + "," + nameCol + r + ")", agg[1], ST_NUMBER));
    }

    /** 按某列分组：重量合计与笔数 (保持首次出现的顺序，空值不计) */
    private static Map<String, double[]> group(List<WasteRecord> records, int col) {
        Map<String, double[]> map = new LinkedHashMap<>();
        for (WasteRecord r : records) {
            String key = r.get(col);
            if (key.isEmpty()) continue;
            double[] agg = map.computeIfAbsent(key, k -> new double[2]);
            agg[0] += Math.max(0, r.getWeight());
            agg[1] += 1;
        }
        return map;
    }

    // ===== XML 片段 =====

    private static StringBuilder sheetStart(double[] widths, int frozenRows) {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
          .append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">")
          .append("<sheetViews><sheetView workbookViewId=\"0\">")
          .append("<pane ySplit=\"").append(frozenRows).append("\" topLeftCell=\"A").append(frozenRows + 1)
          .append("\" activePane=\"bottomLeft\" state=\"frozen\"/>")
          .append("</sheetView></sheetViews><cols>");
        for (int i = 0; i < widths.length; i++) {
            sb.append("<col min=\"").append(i + 1).append("\" max=\"").append(i + 1)
              .append("\" width=\"").append(widths[i]).append("\" customWidth=\"1\"/>");
        }
        return sb.append("</cols><sheetData>");
    }

    private static String sheetEnd(StringBuilder sb, String afterData) {
        sb.append("</sheetData>").append(afterData)
          .append("<pageMargins left=\"0.5\" right=\"0.5\" top=\"0.6\" bottom=\"0.6\" header=\"0.3\" footer=\"0.3\"/>")
          .append("</worksheet>");
        return sb.toString();
    }

    private static StringBuilder row(StringBuilder sb, int r, int height) {
        return sb.append("<row r=\"").append(r).append("\" ht=\"").append(height).append("\" customHeight=\"1\">");
    }

    private static String ref(int col, int row) {
        return ExcelTemplateFiller.getColumnLetter(col + 1) + row;
    }

    private static String str(String ref, String text, int style) {
        if (text == null || text.isEmpty()) return "<c r=\"" + ref + "\" s=\"" + style + "\"/>";
        return "<c r=\"" + ref + "\" s=\"" + style + "\" t=\"inlineStr\"><is><t>" + esc(text) + "</t></is></c>";
    }

    private static String num(String ref, double v, int style) {
        return "<c r=\"" + ref + "\" s=\"" + style + "\"><v>" + fmt(v) + "</v></c>";
    }

    private static String formula(String ref, String f, double cached, int style) {
        return "<c r=\"" + ref + "\" s=\"" + style + "\"><f>" + esc(f) + "</f><v>" + fmt(cached) + "</v></c>";
    }

    private static String fmt(double v) {
        return formatWeight(v);
    }

    /** 整数重量不带小数点 */
    public static String formatWeight(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static void put(ZipOutputStream zip, String name, String content) throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static final String CONTENT_TYPES =
        "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
        + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
        + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
        + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
        + "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>"
        + "<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>"
        + "<Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>"
        + "<Override PartName=\"/xl/worksheets/sheet2.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>"
        + "</Types>";

    private static final String ROOT_RELS =
        "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
        + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
        + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/>"
        + "</Relationships>";

    private static final String WORKBOOK =
        "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
        + "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" "
        + "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">"
        + "<sheets><sheet name=\"" + SHEET1 + "\" sheetId=\"1\" r:id=\"rId1\"/>"
        + "<sheet name=\"" + SHEET2 + "\" sheetId=\"2\" r:id=\"rId2\"/></sheets>"
        + "<calcPr calcId=\"191029\" fullCalcOnLoad=\"1\"/>"
        + "</workbook>";

    private static final String WORKBOOK_RELS =
        "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
        + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
        + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/>"
        + "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet2.xml\"/>"
        + "<Relationship Id=\"rId3\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>"
        + "</Relationships>";

    // 字体：0 常规 / 1 粗体 / 2 标题粗体 16 号；填充：2 表头浅灰；边框：1 细框
    private static final String STYLES =
        "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
        + "<styleSheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">"
        + "<fonts count=\"3\">"
        + "<font><sz val=\"11\"/><name val=\"宋体\"/><family val=\"2\"/><charset val=\"134\"/></font>"
        + "<font><b/><sz val=\"11\"/><name val=\"宋体\"/><family val=\"2\"/><charset val=\"134\"/></font>"
        + "<font><b/><sz val=\"16\"/><name val=\"宋体\"/><family val=\"2\"/><charset val=\"134\"/></font>"
        + "</fonts>"
        + "<fills count=\"3\"><fill><patternFill patternType=\"none\"/></fill><fill><patternFill patternType=\"gray125\"/></fill>"
        + "<fill><patternFill patternType=\"solid\"><fgColor rgb=\"FFE7E6E6\"/><bgColor indexed=\"64\"/></patternFill></fill></fills>"
        + "<borders count=\"2\"><border><left/><right/><top/><bottom/><diagonal/></border>"
        + "<border><left style=\"thin\"><color auto=\"1\"/></left><right style=\"thin\"><color auto=\"1\"/></right>"
        + "<top style=\"thin\"><color auto=\"1\"/></top><bottom style=\"thin\"><color auto=\"1\"/></bottom><diagonal/></border></borders>"
        + "<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>"
        + "<cellXfs count=\"7\">"
        + "<xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/>"
        + "<xf numFmtId=\"0\" fontId=\"2\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyFont=\"1\" applyAlignment=\"1\"><alignment horizontal=\"center\" vertical=\"center\"/></xf>"
        + "<xf numFmtId=\"0\" fontId=\"1\" fillId=\"2\" borderId=\"1\" xfId=\"0\" applyFont=\"1\" applyFill=\"1\" applyBorder=\"1\" applyAlignment=\"1\"><alignment horizontal=\"center\" vertical=\"center\"/></xf>"
        + "<xf numFmtId=\"49\" fontId=\"0\" fillId=\"0\" borderId=\"1\" xfId=\"0\" applyNumberFormat=\"1\" applyBorder=\"1\" applyAlignment=\"1\"><alignment horizontal=\"center\" vertical=\"center\"/></xf>"
        + "<xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"1\" xfId=\"0\" applyBorder=\"1\" applyAlignment=\"1\"><alignment horizontal=\"center\" vertical=\"center\"/></xf>"
        + "<xf numFmtId=\"0\" fontId=\"1\" fillId=\"0\" borderId=\"1\" xfId=\"0\" applyFont=\"1\" applyBorder=\"1\" applyAlignment=\"1\"><alignment horizontal=\"center\" vertical=\"center\"/></xf>"
        + "<xf numFmtId=\"0\" fontId=\"1\" fillId=\"0\" borderId=\"1\" xfId=\"0\" applyFont=\"1\" applyBorder=\"1\" applyAlignment=\"1\"><alignment horizontal=\"center\" vertical=\"center\"/></xf>"
        + "</cellXfs>"
        + "<cellStyles count=\"1\"><cellStyle name=\"常规\" xfId=\"0\" builtinId=\"0\"/></cellStyles>"
        + "</styleSheet>";
}
