package com.parking.checkscanner;

/**
 * 废料登记表的一行：8 列与纸质表一致 (日期 / 回仓部门 / 物品名称规格 / 重量 / 回仓地点 / 跟进人员 / 跟进时间 / 保安人员)，
 * 每格另记一个「需核对」标记 (识别不出或没能对上固定类别时为 true)
 */
public class WasteRecord {

    public static final int COL_DATE = 0;
    public static final int COL_DEPT = 1;
    public static final int COL_ITEM = 2;
    public static final int COL_WEIGHT = 3;
    public static final int COL_LOCATION = 4;
    public static final int COL_FOLLOWER = 5;
    public static final int COL_TIME = 6;
    public static final int COL_GUARD = 7;
    public static final int COL_COUNT = 8;

    public static final String[] COLUMN_NAMES = {
        "日期", "回仓部门", "物品名称/规格", "重量(Kg)", "回仓地点", "跟进人员", "跟进时间", "保安人员"
    };

    // 取值相对固定、按类别清单纠正的列
    public static boolean isCategoryColumn(int col) {
        return col == COL_DEPT || col == COL_ITEM || col == COL_LOCATION || col == COL_FOLLOWER || col == COL_GUARD;
    }

    private final String[] values = new String[COL_COUNT];
    private final boolean[] uncertain = new boolean[COL_COUNT];
    // 手写识别读出的原文 (核对时改了类别列，就把原文记成该值的别名)
    private final String[] raw = new String[COL_COUNT];

    // 本行在照片上的位置 (用于点照片改行、画框)，-1 表示手动添加的行
    public int photoRow = -1;

    public WasteRecord() {
        for (int i = 0; i < COL_COUNT; i++) {
            values[i] = "";
            raw[i] = "";
        }
    }

    public String getRaw(int col) {
        return raw[col];
    }

    public void setRaw(int col, String text) {
        raw[col] = text == null ? "" : text.trim();
    }

    public String get(int col) {
        return values[col];
    }

    public void set(int col, String value, boolean needsCheck) {
        values[col] = value == null ? "" : value.trim();
        uncertain[col] = needsCheck;
    }

    public boolean isUncertain(int col) {
        return uncertain[col];
    }

    public boolean hasUncertain() {
        for (boolean u : uncertain) if (u) return true;
        return false;
    }

    public boolean isEmpty() {
        for (String v : values) if (!v.isEmpty()) return false;
        return true;
    }

    /** 重量 (Kg)，没有或不是数字时返回 -1 */
    public double getWeight() {
        try {
            return values[COL_WEIGHT].isEmpty() ? -1 : Double.parseDouble(values[COL_WEIGHT]);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** 列表里显示的一行摘要，如「10.7 外形加工 · 废铜边框 · 960kg」 */
    public String summary() {
        StringBuilder sb = new StringBuilder();
        if (!values[COL_DATE].isEmpty()) sb.append(values[COL_DATE]).append(' ');
        sb.append(values[COL_DEPT].isEmpty() ? "?" : values[COL_DEPT]);
        sb.append(" · ").append(values[COL_ITEM].isEmpty() ? "?" : values[COL_ITEM]);
        sb.append(" · ").append(values[COL_WEIGHT].isEmpty() ? "?" : values[COL_WEIGHT]).append("kg");
        return sb.toString();
    }
}
