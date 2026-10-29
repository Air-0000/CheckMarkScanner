package com.parking.checkscanner;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 废料登记表的固定类别清单 (回仓部门 / 物品名称 / 回仓地点 / 跟进人员 / 保安人员)：
 * 手写 OCR 读出的文字按清单就近纠正；核对时改成清单外的新值会自动加入清单，也可在菜单里整列编辑。
 * 核对时把某格改成另一个值，会记下「识别原文 → 正确值」的别名：同一人的笔迹下次读出相近原文即可直接对上。
 */
public class WasteCategories {

    private static final String PREF_NAME = "waste_categories";

    // 首次使用的默认清单 (取自现有纸质表)
    private static final String[][] DEFAULTS = new String[WasteRecord.COL_COUNT][];
    static {
        DEFAULTS[WasteRecord.COL_DEPT] = new String[] {
            "D3切板", "切板", "铜箔开料", "D3钻房", "QA/MRB", "外形加工"
        };
        DEFAULTS[WasteRecord.COL_ITEM] = new String[] {
            "切板边料", "废铝片", "废铜边角料", "废铜箔边角料", "废铜箔", "废铜板", "废绿油板",
            "点金边框(沉金)", "废铜边框", "铜边框", "废青粉"
        };
        DEFAULTS[WasteRecord.COL_LOCATION] = new String[] {
            "A楼仓库", "D9仓库"
        };
        DEFAULTS[WasteRecord.COL_FOLLOWER] = new String[] {
            "太公", "邵永松", "陈永金"
        };
        DEFAULTS[WasteRecord.COL_GUARD] = new String[] {
            "黄建江"
        };
    }

    // 低于此相似度视为没对上，保留原文并标为需核对；介于两者之间对上但仍标需核对
    private static final float MIN_MATCH = 0.4f;
    private static final float SURE_MATCH = 0.75f;

    public static class Match {
        public final String value;
        public final boolean needsCheck;
        public final boolean matched; // 对上了清单 (或别名) 里的一项

        Match(String value, boolean needsCheck, boolean matched) {
            this.value = value;
            this.needsCheck = needsCheck;
            this.matched = matched;
        }
    }

    // 每列最多记住的别名条数 (超出时丢掉最早的)
    private static final int MAX_ALIASES = 200;

    private final SharedPreferences sp;

    public WasteCategories(Context context) {
        sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    public List<String> get(int col) {
        String saved = sp.getString(key(col), null);
        if (saved == null) {
            return DEFAULTS[col] == null ? new ArrayList<>() : new ArrayList<>(Arrays.asList(DEFAULTS[col]));
        }
        List<String> list = new ArrayList<>();
        for (String s : saved.split("\n")) {
            if (!s.trim().isEmpty()) list.add(s.trim());
        }
        return list;
    }

    public void set(int col, List<String> values) {
        List<String> clean = new ArrayList<>();
        for (String v : values) {
            String t = v.trim();
            if (!t.isEmpty() && !clean.contains(t)) clean.add(t);
        }
        sp.edit().putString(key(col), String.join("\n", clean)).apply();
    }

    /** 核对时填了清单外的新值：加入该列清单，下次识别即可对上 */
    public void learn(int col, String value) {
        if (!WasteRecord.isCategoryColumn(col) || value == null || value.trim().isEmpty()) return;
        List<String> list = get(col);
        if (list.contains(value.trim())) return;
        list.add(value.trim());
        set(col, list);
    }

    /** 核对时把识别原文 raw 改成了 value：记为别名 (原文与正确值相同、或原文为空时不记) */
    public void learnAlias(int col, String raw, String value) {
        String key = normalize(raw);
        if (!WasteRecord.isCategoryColumn(col) || key.isEmpty() || value == null || value.trim().isEmpty()) return;
        if (key.equals(normalize(value))) return;
        List<String[]> aliases = getAliases(col);
        aliases.removeIf(a -> a[0].equals(key));
        aliases.add(new String[] {key, value.trim()});
        while (aliases.size() > MAX_ALIASES) aliases.remove(0);
        StringBuilder sb = new StringBuilder();
        for (String[] a : aliases) sb.append(a[0]).append('\t').append(a[1]).append('\n');
        sp.edit().putString(aliasKey(col), sb.toString()).apply();
    }

    private List<String[]> getAliases(int col) {
        List<String[]> list = new ArrayList<>();
        String saved = sp.getString(aliasKey(col), "");
        for (String line : saved.split("\n")) {
            String[] parts = line.split("\t");
            if (parts.length == 2) list.add(parts);
        }
        return list;
    }

    /**
     * 把 OCR 原文对到该列清单 (含记下的别名) 里最像的一项 (按最长公共子序列算相似度)；
     * 原文为空返回空值，对不上时保留原文并标需核对
     */
    public Match match(int col, String raw) {
        String text = fixLookalikes(normalize(raw));
        if (text.isEmpty()) return new Match("", false, false);

        String best = null;
        float bestScore = 0;
        for (String candidate : get(col)) {
            float score = similarity(text, fixLookalikes(normalize(candidate)));
            if (score > bestScore) {
                bestScore = score;
                best = candidate;
            }
        }
        for (String[] alias : getAliases(col)) {
            float score = similarity(text, fixLookalikes(alias[0]));
            if (score > bestScore) {
                bestScore = score;
                best = alias[1];
            }
        }
        if (best == null || bestScore < MIN_MATCH) return new Match(raw.trim(), true, false);
        return new Match(best, bestScore < SURE_MATCH, true);
    }

    static String normalize(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            // 全角转半角
            if (c >= 0xFF01 && c <= 0xFF5E) c = (char) (c - 0xFEE0);
            if (Character.isWhitespace(c) || c == '(' || c == ')' || c == '/' || c == '.' || c == '-'
                || c == '·' || c == ',' || c == '、' || c == '|') continue;
            sb.append(Character.toUpperCase(c));
        }
        return sb.toString();
    }

    // 手写识别常把类别里的字读成的形近字 → 原字 (原文与清单两边都换算后再比，不改原文)
    private static final String[][] LOOKALIKES = {
        {"钥锏销铺钢钥", "铜"}, {"铭铅", "铝"}, {"椒极扳枚救叔故瓶", "板"}, {"物刪删班册办", "形"},
        {"筑箫", "箔"}, {"概槟裤医", "框"}, {"正", "工"}, {"店床发乐练军弯后写度", "库"},
        {"包个它", "仓"}, {"差", "房"}, {"多", "片"}, {"爱坚透篮严", "废"}, {"银", "铜"}, {"育", "角"}
    };

    static String fixLookalikes(String s) {
        StringBuilder sb = new StringBuilder(s);
        for (int i = 0; i < sb.length(); i++) {
            for (String[] pair : LOOKALIKES) {
                if (pair[0].indexOf(sb.charAt(i)) >= 0) {
                    sb.setCharAt(i, pair[1].charAt(0));
                    break;
                }
            }
        }
        return sb.toString();
    }

    /**
     * 相似度：最长公共子序列占两者总长的比例 (Dice)；手写常漏字，
     * 原文至少两个字时也看原文有多少落在候选里 (打 8 折)，两者取高
     */
    static float similarity(String a, String b) {
        if (a.isEmpty() || b.isEmpty()) return 0;
        int[][] dp = new int[a.length() + 1][b.length() + 1];
        for (int i = 1; i <= a.length(); i++) {
            for (int j = 1; j <= b.length(); j++) {
                dp[i][j] = a.charAt(i - 1) == b.charAt(j - 1)
                    ? dp[i - 1][j - 1] + 1
                    : Math.max(dp[i - 1][j], dp[i][j - 1]);
            }
        }
        int lcs = dp[a.length()][b.length()];
        float dice = 2f * lcs / (a.length() + b.length());
        float contained = a.length() >= 2 ? 0.8f * lcs / a.length() : 0;
        return Math.max(dice, contained);
    }

    private static String key(int col) {
        return "col_" + col;
    }

    private static String aliasKey(int col) {
        return "alias_" + col;
    }
}
