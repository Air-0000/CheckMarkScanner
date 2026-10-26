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

public class ExcelExporter {

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
}
