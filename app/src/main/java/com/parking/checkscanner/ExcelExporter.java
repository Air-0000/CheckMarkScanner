package com.parking.checkscanner;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import androidx.core.content.FileProvider;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class ExcelExporter {

    /**
     * 导出为 Microsoft Excel (.xlsx) 格式文件
     */
    public static File exportToXlsx(Context context, List<ParkingRecord> records) throws IOException {
        String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
        String fileName = "停车打钩核对表_" + timeStamp + ".xlsx";

        File exportDir = new File(context.getExternalFilesDir(null), "exports");
        if (!exportDir.exists()) {
            exportDir.mkdirs();
        }
        File xlsxFile = new File(exportDir, fileName);

        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(xlsxFile))) {
            // 1. [Content_Types].xml
            writeZipEntry(zos, "[Content_Types].xml", getContentTypesXml());

            // 2. _rels/.rels
            writeZipEntry(zos, "_rels/.rels", getRelsXml());

            // 3. xl/_rels/workbook.xml.rels
            writeZipEntry(zos, "xl/_rels/workbook.xml.rels", getWorkbookRelsXml());

            // 4. xl/workbook.xml
            writeZipEntry(zos, "xl/workbook.xml", getWorkbookXml());

            // 5. xl/styles.xml
            writeZipEntry(zos, "xl/styles.xml", getStylesXml());

            // 6. xl/worksheets/sheet1.xml
            writeZipEntry(zos, "xl/worksheets/sheet1.xml", getSheet1Xml(records));
        }

        return xlsxFile;
    }

    /**
     * 导出为带 UTF-8 BOM 的标准 CSV 文件
     */
    public static File exportToCsv(Context context, List<ParkingRecord> records) throws IOException {
        String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
        String fileName = "停车打钩核对表_" + timeStamp + ".csv";

        File exportDir = new File(context.getExternalFilesDir(null), "exports");
        if (!exportDir.exists()) {
            exportDir.mkdirs();
        }
        File csvFile = new File(exportDir, fileName);

        try (FileOutputStream fos = new FileOutputStream(csvFile);
             OutputStreamWriter writer = new OutputStreamWriter(fos, StandardCharsets.UTF_8)) {
            // 写入 UTF-8 BOM，防止 Excel 打开中文乱码
            fos.write(0xEF);
            fos.write(0xBB);
            fos.write(0xBF);

            writer.write("二号岗亭 停车信息\n");
            writer.write("序号,姓名,车牌,周一白,周一夜,周二白,周二夜,周三白,周三夜,周四白,周四夜,周五白,周五夜,周六白,周六夜,周日白,周日夜,打钩合计,打钩Excel位置\n");

            for (ParkingRecord r : records) {
                StringBuilder line = new StringBuilder();
                line.append(r.getIndex()).append(",");
                line.append(escapeCsv(r.getName())).append(",");
                line.append(escapeCsv(r.getPlateNumber())).append(",");

                for (int c = 0; c < 14; c++) {
                    line.append(r.isShiftChecked(c) ? "1" : "").append(",");
                }
                line.append(r.getCheckedCount()).append(",");
                line.append(escapeCsv(r.getCheckedSummary())).append("\n");
                writer.write(line.toString());
            }

            // 统计行
            int[] shiftTotals = new int[14];
            int grandTotal = 0;
            for (ParkingRecord r : records) {
                for (int c = 0; c < 14; c++) {
                    if (r.isShiftChecked(c)) {
                        shiftTotals[c]++;
                        grandTotal++;
                    }
                }
            }
            StringBuilder totalLine = new StringBuilder("合计,-,-,");
            for (int count : shiftTotals) {
                totalLine.append(count).append(",");
            }
            totalLine.append(grandTotal).append(",\n");
            writer.write(totalLine.toString());
            writer.flush();
        }

        return csvFile;
    }

    private static String escapeCsv(String str) {
        if (str == null) return "";
        if (str.contains(",") || str.contains("\"") || str.contains("\n")) {
            return "\"" + str.replace("\"", "\"\"") + "\"";
        }
        return str;
    }

    /**
     * 调起系统分享 / 微信 / WPS / 邮件等直接打开或发送 Excel 文件
     */
    public static void shareFile(Context context, File file, String mimeType) {
        Uri uri = FileProvider.getUriForFile(context, context.getPackageName() + ".fileprovider", file);
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType(mimeType);
        intent.putExtra(Intent.EXTRA_STREAM, uri);
        intent.putExtra(Intent.EXTRA_SUBJECT, file.getName());
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        context.startActivity(Intent.createChooser(intent, "分享或打开 Excel 表格"));
    }

    private static void writeZipEntry(ZipOutputStream zos, String entryName, String content) throws IOException {
        ZipEntry entry = new ZipEntry(entryName);
        zos.putNextEntry(entry);
        zos.write(content.getBytes(StandardCharsets.UTF_8));
        zos.closeEntry();
    }

    private static String getContentTypesXml() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
               "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">\n" +
               "  <Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>\n" +
               "  <Default Extension=\"xml\" ContentType=\"application/xml\"/>\n" +
               "  <Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>\n" +
               "  <Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>\n" +
               "  <Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>\n" +
               "</Types>";
    }

    private static String getRelsXml() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
               "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">\n" +
               "  <Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/>\n" +
               "</Relationships>";
    }

    private static String getWorkbookRelsXml() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
               "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">\n" +
               "  <Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/>\n" +
               "  <Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>\n" +
               "</Relationships>";
    }

    private static String getWorkbookXml() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
               "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">\n" +
               "  <sheets>\n" +
               "    <sheet name=\"停车信息\" sheetId=\"1\" r:id=\"rId1\"/>\n" +
               "  </sheets>\n" +
               "</workbook>";
    }

    private static String getStylesXml() {
        // 样式 0: 普通无框
        // 样式 1: 表头居中加粗绿色背景+边框
        // 样式 2: 数据单元格居中+边框
        // 样式 3: 打钩单元格浅绿高亮加粗+边框
        // 样式 4: 大标题加粗居中
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
               "<styleSheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">\n" +
               "  <fonts count=\"3\">\n" +
               "    <font><name val=\"Microsoft YaHei\"/><sz val=\"10\"/></font>\n" +
               "    <font><name val=\"Microsoft YaHei\"/><sz val=\"11\"/><b/></font>\n" +
               "    <font><name val=\"Microsoft YaHei\"/><sz val=\"15\"/><b/></font>\n" +
               "  </fonts>\n" +
               "  <fills count=\"4\">\n" +
               "    <fill><patternFill patternType=\"none\"/></fill>\n" +
               "    <fill><patternFill patternType=\"gray125\"/></fill>\n" +
               "    <fill><patternFill patternType=\"solid\"><fgColor rgb=\"FFD7ECD9\"/></patternFill></fill>\n" + // 表头绿
               "    <fill><patternFill patternType=\"solid\"><fgColor rgb=\"FFE8F5E9\"/></patternFill></fill>\n" + // 打钩高亮绿
               "  </fills>\n" +
               "  <borders count=\"2\">\n" +
               "    <border><left/><right/><top/><bottom/></border>\n" +
               "    <border>\n" +
               "      <left style=\"thin\"><color rgb=\"FFB0BEC5\"/></left>\n" +
               "      <right style=\"thin\"><color rgb=\"FFB0BEC5\"/></right>\n" +
               "      <top style=\"thin\"><color rgb=\"FFB0BEC5\"/></top>\n" +
               "      <bottom style=\"thin\"><color rgb=\"FFB0BEC5\"/></bottom>\n" +
               "    </border>\n" +
               "  </borders>\n" +
               "  <cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>\n" +
               "  <cellXfs count=\"5\">\n" +
               "    <xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/>\n" + // 0: 默认
               "    <xf numFmtId=\"0\" fontId=\"1\" fillId=\"2\" borderId=\"1\" xfId=\"0\" applyFont=\"1\" applyFill=\"1\" applyBorder=\"1\"><alignment horizontal=\"center\" vertical=\"center\"/></xf>\n" + // 1: 表头
               "    <xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"1\" xfId=\"0\" applyBorder=\"1\"><alignment horizontal=\"center\" vertical=\"center\"/></xf>\n" + // 2: 数据普通
               "    <xf numFmtId=\"0\" fontId=\"1\" fillId=\"3\" borderId=\"1\" xfId=\"0\" applyFont=\"1\" applyFill=\"1\" applyBorder=\"1\"><alignment horizontal=\"center\" vertical=\"center\"/></xf>\n" + // 3: 打钩高亮
               "    <xf numFmtId=\"0\" fontId=\"2\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyFont=\"1\"><alignment horizontal=\"center\" vertical=\"center\"/></xf>\n" + // 4: 大标题
               "  </cellXfs>\n" +
               "</styleSheet>";
    }

    private static String getSheet1Xml(List<ParkingRecord> records) {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n");
        sb.append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">\n");

        // 列宽配置
        sb.append("  <cols>\n");
        sb.append("    <col min=\"1\" max=\"1\" width=\"7\" customWidth=\"1\"/>\n"); // 序号
        sb.append("    <col min=\"2\" max=\"2\" width=\"12\" customWidth=\"1\"/>\n"); // 姓名
        sb.append("    <col min=\"3\" max=\"3\" width=\"14\" customWidth=\"1\"/>\n"); // 车牌
        for (int i = 4; i <= 17; i++) {
            sb.append("    <col min=\"").append(i).append("\" max=\"").append(i).append("\" width=\"6\" customWidth=\"1\"/>\n");
        }
        sb.append("    <col min=\"18\" max=\"18\" width=\"10\" customWidth=\"1\"/>\n"); // 合计
        sb.append("    <col min=\"19\" max=\"19\" width=\"30\" customWidth=\"1\"/>\n"); // 对应单元格清单
        sb.append("  </cols>\n");

        sb.append("  <sheetData>\n");

        // 第1行: 大标题
        sb.append("    <row r=\"1\" ht=\"32\" customHeight=\"1\">\n");
        sb.append("      <c r=\"A1\" s=\"4\" t=\"inlineStr\"><is><t>").append(escapeXml(ShiftConstants.TABLE_TITLE)).append("</t></is></c>\n");
        sb.append("    </row>\n");

        // 第2行: 星期主表头
        sb.append("    <row r=\"2\" ht=\"22\" customHeight=\"1\">\n");
        sb.append("      <c r=\"A2\" s=\"1\" t=\"inlineStr\"><is><t>序号</t></is></c>\n");
        sb.append("      <c r=\"B2\" s=\"1\" t=\"inlineStr\"><is><t>姓名</t></is></c>\n");
        sb.append("      <c r=\"C2\" s=\"1\" t=\"inlineStr\"><is><t>车牌</t></is></c>\n");
        for (int d = 0; d < 7; d++) {
            String col1 = ShiftConstants.EXCEL_COLUMNS[d * 2];
            String col2 = ShiftConstants.EXCEL_COLUMNS[d * 2 + 1];
            sb.append("      <c r=\"").append(col1).append("2\" s=\"1\" t=\"inlineStr\"><is><t>").append(ShiftConstants.DAYS[d]).append("</t></is></c>\n");
            sb.append("      <c r=\"").append(col2).append("2\" s=\"1\"/>\n");
        }
        sb.append("      <c r=\"R2\" s=\"1\" t=\"inlineStr\"><is><t>打钩小计</t></is></c>\n");
        sb.append("      <c r=\"S2\" s=\"1\" t=\"inlineStr\"><is><t>对应Excel单元格</t></is></c>\n");
        sb.append("    </row>\n");

        // 第3行: 白 / 夜 子表头
        sb.append("    <row r=\"3\" ht=\"20\" customHeight=\"1\">\n");
        sb.append("      <c r=\"A3\" s=\"1\"/>\n");
        sb.append("      <c r=\"B3\" s=\"1\"/>\n");
        sb.append("      <c r=\"C3\" s=\"1\"/>\n");
        for (int c = 0; c < 14; c++) {
            String colLetter = ShiftConstants.EXCEL_COLUMNS[c];
            String shiftSub = (c % 2 == 0) ? "白" : "夜";
            sb.append("      <c r=\"").append(colLetter).append("3\" s=\"1\" t=\"inlineStr\"><is><t>").append(shiftSub).append("</t></is></c>\n");
        }
        sb.append("      <c r=\"R3\" s=\"1\"/>\n");
        sb.append("      <c r=\"S3\" s=\"1\"/>\n");
        sb.append("    </row>\n");

        // 数据行 (从第4行起)
        int[] shiftTotals = new int[14];
        int grandTotal = 0;

        for (ParkingRecord record : records) {
            int rowNum = record.getExcelRow();
            sb.append("    <row r=\"").append(rowNum).append("\" ht=\"20\" customHeight=\"1\">\n");
            // A: 序号
            sb.append("      <c r=\"A").append(rowNum).append("\" s=\"2\"><v>").append(record.getIndex()).append("</v></c>\n");
            // B: 姓名
            sb.append("      <c r=\"B").append(rowNum).append("\" s=\"2\" t=\"inlineStr\"><is><t>").append(escapeXml(record.getName())).append("</t></is></c>\n");
            // C: 车牌
            sb.append("      <c r=\"C").append(rowNum).append("\" s=\"2\" t=\"inlineStr\"><is><t>").append(escapeXml(record.getPlateNumber())).append("</t></is></c>\n");

            // D ~ Q: 14个班次单元格
            for (int c = 0; c < 14; c++) {
                String col = ShiftConstants.EXCEL_COLUMNS[c];
                boolean isChecked = record.isShiftChecked(c);
                if (isChecked) {
                    shiftTotals[c]++;
                    grandTotal++;
                    sb.append("      <c r=\"").append(col).append(rowNum).append("\" s=\"3\"><v>1</v></c>\n");
                } else {
                    sb.append("      <c r=\"").append(col).append(rowNum).append("\" s=\"2\"/>\n");
                }
            }

            // R: 小计
            sb.append("      <c r=\"R").append(rowNum).append("\" s=\"2\"><v>").append(record.getCheckedCount()).append("</v></c>\n");
            // S: 明细
            sb.append("      <c r=\"S").append(rowNum).append("\" s=\"2\" t=\"inlineStr\"><is><t>").append(escapeXml(record.getCheckedSummary())).append("</t></is></c>\n");

            sb.append("    </row>\n");
        }

        // 合计汇总行 (动态放置在最后一行数据之后)
        int maxDataRow = 3;
        for (ParkingRecord record : records) {
            if (record.getExcelRow() > maxDataRow) {
                maxDataRow = record.getExcelRow();
            }
        }
        int totalRow = maxDataRow + 1;
        sb.append("    <row r=\"").append(totalRow).append("\" ht=\"24\" customHeight=\"1\">\n");
        sb.append("      <c r=\"A").append(totalRow).append("\" s=\"1\" t=\"inlineStr\"><is><t>合计</t></is></c>\n");
        sb.append("      <c r=\"B").append(totalRow).append("\" s=\"1\"/>\n");
        sb.append("      <c r=\"C").append(totalRow).append("\" s=\"1\"/>\n");
        for (int c = 0; c < 14; c++) {
            String col = ShiftConstants.EXCEL_COLUMNS[c];
            sb.append("      <c r=\"").append(col).append(totalRow).append("\" s=\"1\"><v>").append(shiftTotals[c]).append("</v></c>\n");
        }
        sb.append("      <c r=\"R").append(totalRow).append("\" s=\"1\"><v>").append(grandTotal).append("</v></c>\n");
        sb.append("      <c r=\"S").append(totalRow).append("\" s=\"1\" t=\"inlineStr\"><is><t>共 ").append(grandTotal).append(" 次出勤 (已填1)</t></is></c>\n");
        sb.append("    </row>\n");

        sb.append("  </sheetData>\n");

        // 合并单元格定义
        sb.append("  <mergeCells count=\"9\">\n");
        sb.append("    <mergeCell ref=\"A1:Q1\"/>\n"); // 标题合并
        sb.append("    <mergeCell ref=\"A2:A3\"/>\n"); // 序号
        sb.append("    <mergeCell ref=\"B2:B3\"/>\n"); // 姓名
        sb.append("    <mergeCell ref=\"C2:C3\"/>\n"); // 车牌
        sb.append("    <mergeCell ref=\"D2:E2\"/>\n"); // 周一
        sb.append("    <mergeCell ref=\"F2:G2\"/>\n"); // 周二
        sb.append("    <mergeCell ref=\"H2:I2\"/>\n"); // 周三
        sb.append("    <mergeCell ref=\"J2:K2\"/>\n"); // 周四
        sb.append("    <mergeCell ref=\"L2:M2\"/>\n"); // 周五
        sb.append("    <mergeCell ref=\"N2:O2\"/>\n"); // 周六
        sb.append("    <mergeCell ref=\"P2:Q2\"/>\n"); // 周日
        sb.append("  </mergeCells>\n");

        sb.append("</worksheet>");
        return sb.toString();
    }

    private static String escapeXml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }
}
