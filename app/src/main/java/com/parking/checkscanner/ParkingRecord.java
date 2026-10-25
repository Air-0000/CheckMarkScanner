package com.parking.checkscanner;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

public class ParkingRecord implements Serializable {
    private int index;
    private String name;
    private String plateNumber;
    private boolean[] checkedShifts;
    private float[] confidences;

    public ParkingRecord(int index, String name, String plateNumber) {
        this.index = index;
        this.name = name;
        this.plateNumber = plateNumber;
        this.checkedShifts = new boolean[14];
        this.confidences = new float[14];
    }

    public int getIndex() {
        return index;
    }

    public void setIndex(int index) {
        this.index = index;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getPlateNumber() {
        return plateNumber;
    }

    public void setPlateNumber(String plateNumber) {
        this.plateNumber = plateNumber;
    }

    public boolean isShiftChecked(int shiftIndex) {
        if (shiftIndex >= 0 && shiftIndex < checkedShifts.length) {
            return checkedShifts[shiftIndex];
        }
        return false;
    }

    public void setShiftChecked(int shiftIndex, boolean checked) {
        if (shiftIndex >= 0 && shiftIndex < checkedShifts.length) {
            checkedShifts[shiftIndex] = checked;
        }
    }

    public void toggleShift(int shiftIndex) {
        if (shiftIndex >= 0 && shiftIndex < checkedShifts.length) {
            checkedShifts[shiftIndex] = !checkedShifts[shiftIndex];
        }
    }

    public float getConfidence(int shiftIndex) {
        if (shiftIndex >= 0 && shiftIndex < confidences.length) {
            return confidences[shiftIndex];
        }
        return 0f;
    }

    public void setConfidence(int shiftIndex, float conf) {
        if (shiftIndex >= 0 && shiftIndex < confidences.length) {
            confidences[shiftIndex] = conf;
        }
    }

    public void copyShiftsFrom(ParkingRecord other) {
        if (other == null) return;
        for (int i = 0; i < 14; i++) {
            this.checkedShifts[i] = other.isShiftChecked(i);
            this.confidences[i] = other.getConfidence(i);
        }
    }

    /**
     * Excel 中的行号。
     * 第1行: 标题
     * 第2行: 星期表头
     * 第3行: 白/夜表头
     * 第4行起: 序号 1 数据
     */
    public int getExcelRow() {
        return index + 3;
    }

    /**
     * 获取指定班次的 Excel 单元格标识，如 "D5", "J5"
     */
    public String getExcelCell(int shiftIndex) {
        if (shiftIndex >= 0 && shiftIndex < ShiftConstants.EXCEL_COLUMNS.length) {
            return ShiftConstants.EXCEL_COLUMNS[shiftIndex] + getExcelRow();
        }
        return "";
    }

    public int getCheckedCount() {
        int count = 0;
        for (boolean b : checkedShifts) {
            if (b) count++;
        }
        return count;
    }

    public boolean hasAnyChecked() {
        return getCheckedCount() > 0;
    }

    /**
     * 获取详细勾选与 Excel 映射描述，如: "周一白(D5), 周二白(F5), 周四白(J5)"
     */
    public String getCheckedSummary() {
        List<String> list = new ArrayList<>();
        for (int i = 0; i < 14; i++) {
            if (checkedShifts[i]) {
                list.add(ShiftConstants.SHIFT_NAMES[i] + "(" + getExcelCell(i) + ")");
            }
        }
        if (list.isEmpty()) {
            return "无打钩";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(list.get(i));
        }
        return sb.toString();
    }
}
