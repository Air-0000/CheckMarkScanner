package com.parking.checkscanner;

import android.content.Context;
import android.net.Uri;
import android.util.Log;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Excel 模板自动填报工具：
 * 支持导入任意 Excel (.xlsx) 模板，根据图片识别出的打钩考勤结果，
 * 精准在对应人员与对应班次单元格填入数值 1，并保留原有格式、表头与公式。
 */
public class ExcelTemplateFiller {

    private static final String TAG = "ExcelTemplateFiller";

    public static class FillResult {
        public boolean success;
        public File outputFile;
        public int filledMarksCount;
        public int attendedPersonsCount;
        public String message;

        public FillResult(boolean success, File outputFile, int filledMarksCount, int attendedPersonsCount, String message) {
            this.success = success;
            this.outputFile = outputFile;
            this.filledMarksCount = filledMarksCount;
            this.attendedPersonsCount = attendedPersonsCount;
            this.message = message;
        }
    }

    /**
     * 使用内置《停车情况》模板 (assets/template_parking_status.xlsx) 的副本进行自动填报，模板本身不改动
     */
    public static FillResult fillBuiltInTemplate(Context context, List<ParkingRecord> records) {
        try {
            InputStream is = context.getAssets().open("template_parking_status.xlsx");
            return fillExcel(context, is, records, "停车情况");
        } catch (Exception e) {
            Log.e(TAG, "内置模板填报失败: " + e.getMessage(), e);
            return new FillResult(false, null, 0, 0, "读取内置模板失败: " + e.getMessage());
        }
    }

    /**
     * 导入用户从手机或微信选择的外部 Excel 文件 (.xlsx) 进行自动填报
     */
    public static FillResult fillUserExcel(Context context, Uri excelUri, List<ParkingRecord> records) {
        try {
            InputStream is = context.getContentResolver().openInputStream(excelUri);
            if (is == null) {
                return new FillResult(false, null, 0, 0, "无法打开所选 Excel 文件");
            }
            return fillExcel(context, is, records, "停车情况");
        } catch (Exception e) {
            Log.e(TAG, "外部 Excel 填报失败: " + e.getMessage(), e);
            return new FillResult(false, null, 0, 0, "导入外部 Excel 处理失败: " + e.getMessage());
        }
    }

    public static FillResult fillExcel(Context context, InputStream templateInputStream, List<ParkingRecord> records, String baseFileName) {
        // 按导出当天所在的周 (周一 ~ 周日) 写表头日期并命名，如 停车情况10.19-10.25.xlsx
        LocalDate weekMonday = LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        File exportDir = new File(context.getExternalFilesDir(null), "exports");
        if (!exportDir.exists()) {
            exportDir.mkdirs();
        }
        File outputFile = new File(exportDir, baseFileName + formatWeekRange(weekMonday) + ".xlsx");
        return fillExcel(templateInputStream, outputFile, context.getCacheDir(), records, weekMonday);
    }

    /**
     * 周区间文字，如 "10.19-10.25"
     */
    public static String formatWeekRange(LocalDate weekMonday) {
        DateTimeFormatter f = DateTimeFormatter.ofPattern("M.d");
        return weekMonday.format(f) + "-" + weekMonday.plusDays(6).format(f);
    }

    /**
     * 核心填报引擎：
     * 读取 .xlsx ZIP 压缩包，解析 sharedStrings 与 sheet1.xml，
     * 智能匹配考勤行与班次列，将打钩处填入数值 1，重新封装为标准 .xlsx。
     * weekMonday 不为空时，表头日期格同步改为该周周一 ~ 周日。
     */
    public static FillResult fillExcel(InputStream templateInputStream, File outputFile, File cacheDir, List<ParkingRecord> records, LocalDate weekMonday) {
        File tempInputFile = null;
        ZipFile zipFile = null;
        try {
            // 1. 将输入流暂存为本地缓存文件，以便随机读取 ZIP Entry
            tempInputFile = File.createTempFile("excel_in_", ".xlsx", cacheDir);
            try (FileOutputStream fos = new FileOutputStream(tempInputFile)) {
                byte[] buffer = new byte[8192];
                int len;
                while ((len = templateInputStream.read(buffer)) > 0) {
                    fos.write(buffer, 0, len);
                }
            } finally {
                templateInputStream.close();
            }

            zipFile = new ZipFile(tempInputFile);

            // 2. 解析 xl/sharedStrings.xml (若存在)
            List<String> sharedStrings = parseSharedStrings(zipFile);

            // 3. 找到目标 Worksheet (默认 sheet1.xml)
            String targetSheetEntryName = findWorksheetEntryName(zipFile);
            ZipEntry sheetEntry = zipFile.getEntry(targetSheetEntryName);
            if (sheetEntry == null) {
                return new FillResult(false, null, 0, 0, "Excel 结构异常：未找到工作表 (Worksheet)");
            }

            // 4. 解析 Worksheet XML 为 DOM
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            dbf.setNamespaceAware(false);
            DocumentBuilder db = dbf.newDocumentBuilder();
            Document sheetDoc;
            try (InputStream sheetIs = zipFile.getInputStream(sheetEntry)) {
                sheetDoc = db.parse(sheetIs);
            }

            // 5. 扫描所有 <row> 建立行映射并填入 1
            NodeList rowNodes = sheetDoc.getElementsByTagName("row");
            int rowCount = rowNodes.getLength();

            // 表头 (标题/序号姓名车牌/日期/星期/白夜) 之后才是人员数据行，表头行一律不写
            int dataStartRow = detectDataStartRow(rowNodes, sharedStrings);

            // 解析班次列映射 (从表头行中嗅探，若无则使用默认 D~Q)
            Map<Integer, String> shiftToColMap = detectShiftColumns(rowNodes, sharedStrings, dataStartRow);

            if (weekMonday != null) {
                updateWeekDates(rowNodes, sharedStrings, dataStartRow, weekMonday);
            }

            // 构建姓名/车牌/序号到 ParkingRecord 的快速索引
            Map<String, ParkingRecord> nameMap = new HashMap<>();
            Map<String, ParkingRecord> plateMap = new HashMap<>();
            Map<Integer, ParkingRecord> indexMap = new HashMap<>();
            for (ParkingRecord r : records) {
                if (r.getName() != null && !r.getName().isEmpty()) {
                    nameMap.put(r.getName().trim(), r);
                }
                if (r.getPlateNumber() != null && !r.getPlateNumber().isEmpty()) {
                    plateMap.put(r.getPlateNumber().trim().toUpperCase(Locale.ROOT), r);
                }
                indexMap.put(r.getIndex(), r);
            }

            int filledMarksCount = 0;
            int attendedPersonsCount = 0;

            // 记录哪些记录已在表格中完成填报
            Map<ParkingRecord, Boolean> processedRecords = new HashMap<>();

            for (int i = 0; i < rowCount; i++) {
                Element rowElem = (Element) rowNodes.item(i);
                int rowNum = parseRowNumber(rowElem.getAttribute("r"), i + 1);
                if (rowNum < dataStartRow) continue;

                // 尝试从当前行的单元格匹配人员
                ParkingRecord matchedRecord = matchRecordForRow(rowElem, sharedStrings, nameMap, plateMap, indexMap, rowNum, dataStartRow);

                // 同一人只填一行，避免 OCR 误识的姓名/车牌撞上表中其他人时重复填写
                if (matchedRecord != null && !processedRecords.containsKey(matchedRecord)) {
                    boolean hadMark = false;
                    for (int shiftIdx = 0; shiftIdx < 14; shiftIdx++) {
                        if (matchedRecord.isShiftChecked(shiftIdx)) {
                            hadMark = true;
                            String colLetter = shiftToColMap.get(shiftIdx);
                            if (colLetter == null) {
                                colLetter = ShiftConstants.EXCEL_COLUMNS[shiftIdx];
                            }
                            String targetCellRef = colLetter + rowNum;

                            // 在该行寻找或插入对应单元格并设为 1
                            setCellNumberOne(sheetDoc, rowElem, targetCellRef);
                            filledMarksCount++;
                        }
                    }

                    if (hadMark) {
                        attendedPersonsCount++;
                    }
                    processedRecords.put(matchedRecord, true);
                }
            }

            // 若表格中没有对应人员行（例如极简外部表格），补充按坐标直填
            for (ParkingRecord r : records) {
                if (r.hasAnyChecked() && !processedRecords.containsKey(r)) {
                    int targetRow = dataStartRow + r.getIndex() - 1;
                    Element rowElem = findOrCreateRow(sheetDoc, targetRow);
                    for (int shiftIdx = 0; shiftIdx < 14; shiftIdx++) {
                        if (r.isShiftChecked(shiftIdx)) {
                            String colLetter = shiftToColMap.get(shiftIdx);
                            if (colLetter == null) {
                                colLetter = ShiftConstants.EXCEL_COLUMNS[shiftIdx];
                            }
                            String targetCellRef = colLetter + targetRow;
                            setCellNumberOne(sheetDoc, rowElem, targetCellRef);
                            filledMarksCount++;
                        }
                    }
                    attendedPersonsCount++;
                }
            }

            // 6. 将修改后的 XML 与其余原有 Entry 写入输出文件
            if (outputFile.getParentFile() != null && !outputFile.getParentFile().exists()) {
                outputFile.getParentFile().mkdirs();
            }
            try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(outputFile))) {
                Enumeration<? extends ZipEntry> entries = zipFile.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    String name = entry.getName();

                    ZipEntry outEntry = new ZipEntry(name);
                    zos.putNextEntry(outEntry);

                    if (name.equals(targetSheetEntryName)) {
                        // 写入修改后的 Worksheet XML
                        TransformerFactory tf = TransformerFactory.newInstance();
                        Transformer t = tf.newTransformer();
                        t.transform(new DOMSource(sheetDoc), new StreamResult(zos));
                    } else {
                        // 完整复制其它原文件 (包括 styles.xml, sharedStrings.xml, 其它工作表等)
                        try (InputStream entryIs = zipFile.getInputStream(entry)) {
                            byte[] buf = new byte[8192];
                            int readLen;
                            while ((readLen = entryIs.read(buf)) > 0) {
                                zos.write(buf, 0, readLen);
                            }
                        }
                    }
                    zos.closeEntry();
                }
            }

            return new FillResult(true, outputFile, filledMarksCount, attendedPersonsCount, "填报成功");

        } catch (Exception e) {
            Log.e(TAG, "Excel 自动填报处理异常: " + e.getMessage(), e);
            return new FillResult(false, null, 0, 0, "处理异常: " + e.getMessage());
        } finally {
            if (zipFile != null) {
                try { zipFile.close(); } catch (Exception ignored) {}
            }
            if (tempInputFile != null && tempInputFile.exists()) {
                tempInputFile.delete();
            }
        }
    }

    /**
     * 在指定 <row> 元素中查找目标单元格，若无则插入，并将值赋为数字 1
     */
    private static void setCellNumberOne(Document doc, Element rowElem, String cellRef) {
        String targetCol = getCellColumn(cellRef);
        int targetColIdx = getColumnIndex(targetCol);

        Element targetCell = null;
        Node insertBeforeNode = null;

        NodeList childCells = rowElem.getElementsByTagName("c");
        int count = childCells.getLength();
        for (int i = 0; i < count; i++) {
            Element c = (Element) childCells.item(i);
            String r = c.getAttribute("r");
            if (cellRef.equalsIgnoreCase(r)) {
                targetCell = c;
                break;
            }
            String col = getCellColumn(r);
            int colIdx = getColumnIndex(col);
            if (colIdx > targetColIdx && insertBeforeNode == null) {
                insertBeforeNode = c;
            }
        }

        if (targetCell == null) {
            targetCell = doc.createElement("c");
            targetCell.setAttribute("r", cellRef);
            // 沿用行内同类数据单元格样式(如边框/居中)
            if (count > 0) {
                Element sample = (Element) childCells.item(0);
                if (sample.hasAttribute("s")) {
                    targetCell.setAttribute("s", sample.getAttribute("s"));
                }
            }
            if (insertBeforeNode != null) {
                rowElem.insertBefore(targetCell, insertBeforeNode);
            } else {
                rowElem.appendChild(targetCell);
            }
        }

        // 清空原有的字符或勾选内容
        while (targetCell.hasChildNodes()) {
            targetCell.removeChild(targetCell.getFirstChild());
        }

        // 设置为纯数字类型 1 (在 Excel 中可直接用 SUM 求和)
        targetCell.setAttribute("t", "n");
        Element vElem = doc.createElement("v");
        vElem.setTextContent("1");
        targetCell.appendChild(vElem);
    }

    /**
     * 智能匹配该行对应的人员记录
     */
    private static ParkingRecord matchRecordForRow(Element rowElem, List<String> sharedStrings,
                                                  Map<String, ParkingRecord> nameMap,
                                                  Map<String, ParkingRecord> plateMap,
                                                  Map<Integer, ParkingRecord> indexMap,
                                                  int rowNum, int dataStartRow) {
        NodeList cells = rowElem.getElementsByTagName("c");
        for (int i = 0; i < cells.getLength(); i++) {
            Element c = (Element) cells.item(i);
            String val = getCellStringValue(c, sharedStrings).trim();
            if (val.isEmpty()) continue;

            // 1. 姓名匹配 (精准或包含)
            if (nameMap.containsKey(val)) {
                return nameMap.get(val);
            }
            for (Map.Entry<String, ParkingRecord> entry : nameMap.entrySet()) {
                String n = entry.getKey();
                if (n.length() >= 2 && val.contains(n)) {
                    return entry.getValue();
                }
            }

            // 2. 车牌号匹配 (忽略大小写)
            String upperVal = val.toUpperCase(Locale.ROOT);
            if (plateMap.containsKey(upperVal)) {
                return plateMap.get(upperVal);
            }
            for (Map.Entry<String, ParkingRecord> entry : plateMap.entrySet()) {
                String p = entry.getKey();
                if (p.length() >= 5 && upperVal.contains(p)) {
                    return entry.getValue();
                }
            }

            // 3. 按 A 列序号匹配
            String r = c.getAttribute("r");
            if (r.startsWith("A") || i == 0) {
                try {
                    int parsedIdx = Integer.parseInt(val);
                    if (indexMap.containsKey(parsedIdx)) {
                        return indexMap.get(parsedIdx);
                    }
                } catch (Exception ignored) {}
            }
        }

        // 4. 若单元格无字，按数据起始行估算序号 (第一条数据行为序号 1)
        int fallbackIndex = rowNum - dataStartRow + 1;
        if (indexMap.containsKey(fallbackIndex)) {
            return indexMap.get(fallbackIndex);
        }

        return null;
    }

    /**
     * 自动嗅探工作表中的 14 个班次对应列：
     * 支持单行表头 (如: 周一白、周一夜)，以及双行合并表头 (上一行: 星期一，下一行: 白 / 夜)
     */
    private static Map<Integer, String> detectShiftColumns(NodeList rowNodes, List<String> sharedStrings, int dataStartRow) {
        Map<Integer, String> map = new HashMap<>();
        // 预设默认列 D~Q
        for (int i = 0; i < 14; i++) {
            map.put(i, ShiftConstants.EXCEL_COLUMNS[i]);
        }

        int scanRows = countRowsBefore(rowNodes, dataStartRow);

        // 1. 扫描星期主表头 (如星期一/周一 所在列及相邻合并列)
        Map<String, Integer> colToDayMap = new HashMap<>();
        for (int r = 0; r < scanRows; r++) {
            Element row = (Element) rowNodes.item(r);
            NodeList cells = row.getElementsByTagName("c");
            for (int c = 0; c < cells.getLength(); c++) {
                Element cell = (Element) cells.item(c);
                String text = getCellStringValue(cell, sharedStrings).trim();
                String col = getCellColumn(cell.getAttribute("r"));
                int colIdx = getColumnIndex(col);

                for (int d = 0; d < 7; d++) {
                    String day = ShiftConstants.DAYS[d]; // "星期一"
                    String shortDay = "周" + "一二三四五六日".charAt(d); // "周一"
                    if (text.contains(day) || text.contains(shortDay)) {
                        colToDayMap.put(col, d);
                        colToDayMap.put(getColumnLetter(colIdx + 1), d);
                    }
                }
            }
        }

        // 2. 扫描班次子表头 (白/夜) 或单行综合表头 (周一白)
        for (int r = 0; r < scanRows; r++) {
            Element row = (Element) rowNodes.item(r);
            NodeList cells = row.getElementsByTagName("c");
            for (int c = 0; c < cells.getLength(); c++) {
                Element cell = (Element) cells.item(c);
                String text = getCellStringValue(cell, sharedStrings).trim();
                String col = getCellColumn(cell.getAttribute("r"));

                // 情况 A: 双行表头，结合 colToDayMap
                Integer day = colToDayMap.get(col);
                if (day != null) {
                    if (text.contains("白") || text.equals("白")) {
                        map.put(day * 2, col);
                    } else if (text.contains("夜") || text.equals("夜")) {
                        map.put(day * 2 + 1, col);
                    }
                }

                // 情况 B: 单行表头
                for (int s = 0; s < 14; s++) {
                    String shiftName = ShiftConstants.SHIFT_NAMES[s];
                    String dayName = ShiftConstants.DAYS[s / 2];
                    String sub = (s % 2 == 0) ? "白" : "夜";

                    if (text.contains(shiftName) || (text.contains(dayName) && text.contains(sub))) {
                        map.put(s, col);
                    }
                }
            }
        }

        return map;
    }

    /**
     * 嗅探表头所占行数，返回第一条人员数据所在行号：
     * 前 10 行中出现 序号/姓名/车牌/星期X/白/夜 等表头字样的最后一行之后即为数据区。
     * 内置《停车情况》模板: 1~3 行标题、4 行序号/日期、5 行星期、6 行白夜 → 第 7 行起为数据。
     * 未找到表头时沿用旧版布局 (第 4 行起为数据)。
     */
    private static int detectDataStartRow(NodeList rowNodes, List<String> sharedStrings) {
        int lastHeaderRow = 0;
        int scanRows = Math.min(rowNodes.getLength(), 10);
        for (int r = 0; r < scanRows; r++) {
            Element row = (Element) rowNodes.item(r);
            int rowNum = parseRowNumber(row.getAttribute("r"), r + 1);
            NodeList cells = row.getElementsByTagName("c");
            for (int c = 0; c < cells.getLength(); c++) {
                String text = getCellStringValue((Element) cells.item(c), sharedStrings).trim();
                if (text.equals("序号") || text.equals("姓名") || text.startsWith("车牌")
                        || text.equals("白") || text.equals("夜") || text.startsWith("星期")) {
                    lastHeaderRow = Math.max(lastHeaderRow, rowNum);
                    break;
                }
            }
        }
        return lastHeaderRow > 0 ? lastHeaderRow + 1 : 4;
    }

    /**
     * 将表头里的日期格改为当周日期：与「星期X」同列、位于其它表头行的纯数值 Excel 日期 (序列值)。
     * 内置模板: D4/F4/H4/J4/L4/N4/P4 → 周一 ~ 周日。文字日期与公式格保持原样。
     */
    private static void updateWeekDates(NodeList rowNodes, List<String> sharedStrings, int dataStartRow, LocalDate weekMonday) {
        int headerRows = countRowsBefore(rowNodes, dataStartRow);

        // 星期表头: 列 → 第几天 (0 = 周一)，以及所在行号
        Map<String, Integer> colToDay = new HashMap<>();
        Map<String, Integer> colToWeekdayRow = new HashMap<>();
        for (int r = 0; r < headerRows; r++) {
            Element row = (Element) rowNodes.item(r);
            int rowNum = parseRowNumber(row.getAttribute("r"), r + 1);
            NodeList cells = row.getElementsByTagName("c");
            for (int c = 0; c < cells.getLength(); c++) {
                Element cell = (Element) cells.item(c);
                String text = getCellStringValue(cell, sharedStrings).trim();
                for (int d = 0; d < 7; d++) {
                    String shortDay = "周" + "一二三四五六日".charAt(d);
                    if (text.contains(ShiftConstants.DAYS[d]) || text.contains(shortDay)) {
                        String col = getCellColumn(cell.getAttribute("r"));
                        colToDay.put(col, d);
                        colToWeekdayRow.put(col, rowNum);
                        break;
                    }
                }
            }
        }

        // 1899-12-30 为 Excel 日期序列 0，对应 epochDay -25569
        long mondaySerial = weekMonday.toEpochDay() + 25569;
        for (int r = 0; r < headerRows; r++) {
            Element row = (Element) rowNodes.item(r);
            int rowNum = parseRowNumber(row.getAttribute("r"), r + 1);
            NodeList cells = row.getElementsByTagName("c");
            for (int c = 0; c < cells.getLength(); c++) {
                Element cell = (Element) cells.item(c);
                String col = getCellColumn(cell.getAttribute("r"));
                Integer day = colToDay.get(col);
                if (day == null || rowNum == colToWeekdayRow.get(col)) continue;

                String type = cell.getAttribute("t");
                if (!(type.isEmpty() || "n".equals(type))) continue;
                if (cell.getElementsByTagName("f").getLength() > 0) continue;
                NodeList vList = cell.getElementsByTagName("v");
                if (vList.getLength() == 0) continue;
                double serial;
                try {
                    serial = Double.parseDouble(vList.item(0).getTextContent().trim());
                } catch (Exception e) {
                    continue;
                }
                // 只认 1982 ~ 2119 年范围内的日期序列值，避免误改其它数字
                if (serial < 30000 || serial > 80000) continue;

                vList.item(0).setTextContent(String.valueOf(mondaySerial + day));
            }
        }
    }

    private static int countRowsBefore(NodeList rowNodes, int rowNumExclusive) {
        int count = 0;
        for (int i = 0; i < rowNodes.getLength(); i++) {
            Element row = (Element) rowNodes.item(i);
            if (parseRowNumber(row.getAttribute("r"), i + 1) >= rowNumExclusive) break;
            count++;
        }
        return count;
    }

    private static Element findOrCreateRow(Document doc, int targetRowNum) {
        NodeList rows = doc.getElementsByTagName("row");
        Element sheetData = null;
        NodeList sdList = doc.getElementsByTagName("sheetData");
        if (sdList.getLength() > 0) {
            sheetData = (Element) sdList.item(0);
        }

        Node insertBefore = null;
        for (int i = 0; i < rows.getLength(); i++) {
            Element r = (Element) rows.item(i);
            int rNum = parseRowNumber(r.getAttribute("r"), i + 1);
            if (rNum == targetRowNum) {
                return r;
            }
            if (rNum > targetRowNum && insertBefore == null) {
                insertBefore = r;
            }
        }

        Element newRow = doc.createElement("row");
        newRow.setAttribute("r", String.valueOf(targetRowNum));
        if (sheetData != null) {
            if (insertBefore != null) {
                sheetData.insertBefore(newRow, insertBefore);
            } else {
                sheetData.appendChild(newRow);
            }
        }
        return newRow;
    }

    private static String findWorksheetEntryName(ZipFile zipFile) {
        if (zipFile.getEntry("xl/worksheets/sheet1.xml") != null) {
            return "xl/worksheets/sheet1.xml";
        }
        Enumeration<? extends ZipEntry> entries = zipFile.entries();
        while (entries.hasMoreElements()) {
            ZipEntry e = entries.nextElement();
            if (e.getName().startsWith("xl/worksheets/sheet") && e.getName().endsWith(".xml")) {
                return e.getName();
            }
        }
        return "xl/worksheets/sheet1.xml";
    }

    private static List<String> parseSharedStrings(ZipFile zipFile) {
        List<String> list = new ArrayList<>();
        ZipEntry entry = zipFile.getEntry("xl/sharedStrings.xml");
        if (entry == null) return list;

        try (InputStream is = zipFile.getInputStream(entry)) {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            dbf.setNamespaceAware(false);
            Document doc = dbf.newDocumentBuilder().parse(is);
            NodeList siList = doc.getElementsByTagName("si");
            for (int i = 0; i < siList.getLength(); i++) {
                Element si = (Element) siList.item(i);
                NodeList tList = si.getElementsByTagName("t");
                StringBuilder sb = new StringBuilder();
                for (int j = 0; j < tList.getLength(); j++) {
                    sb.append(tList.item(j).getTextContent());
                }
                list.add(sb.toString());
            }
        } catch (Exception e) {
            Log.e(TAG, "解析 sharedStrings.xml 失败: " + e.getMessage());
        }
        return list;
    }

    private static String getCellStringValue(Element cell, List<String> sharedStrings) {
        if (cell == null) return "";
        String type = cell.getAttribute("t");
        if ("s".equals(type)) {
            NodeList vList = cell.getElementsByTagName("v");
            if (vList.getLength() > 0) {
                try {
                    int idx = Integer.parseInt(vList.item(0).getTextContent().trim());
                    if (idx >= 0 && idx < sharedStrings.size()) {
                        return sharedStrings.get(idx);
                    }
                } catch (Exception ignored) {}
            }
            return "";
        } else if ("inlineStr".equals(type)) {
            NodeList tList = cell.getElementsByTagName("t");
            if (tList.getLength() > 0) {
                return tList.item(0).getTextContent();
            }
        } else {
            NodeList vList = cell.getElementsByTagName("v");
            if (vList.getLength() > 0) {
                return vList.item(0).getTextContent();
            }
        }
        return "";
    }

    public static String getCellColumn(String cellRef) {
        if (cellRef == null) return "";
        return cellRef.replaceAll("[0-9]", "").toUpperCase(Locale.ROOT);
    }

    public static String getColumnLetter(int colIndex) {
        StringBuilder sb = new StringBuilder();
        while (colIndex > 0) {
            int rem = (colIndex - 1) % 26;
            sb.insert(0, (char) ('A' + rem));
            colIndex = (colIndex - 1) / 26;
        }
        return sb.toString();
    }

    public static int getColumnIndex(String colLetter) {
        int col = 0;
        for (int i = 0; i < colLetter.length(); i++) {
            char ch = colLetter.charAt(i);
            if (ch >= 'A' && ch <= 'Z') {
                col = col * 26 + (ch - 'A' + 1);
            } else if (ch >= 'a' && ch <= 'z') {
                col = col * 26 + (ch - 'a' + 1);
            }
        }
        return col;
    }

    private static int parseRowNumber(String rAttr, int defaultNum) {
        try {
            return Integer.parseInt(rAttr);
        } catch (Exception e) {
            return defaultNum;
        }
    }
}
