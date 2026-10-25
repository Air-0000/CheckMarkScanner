package com.parking.checkscanner;

import java.util.ArrayList;
import java.util.List;

public class ShiftConstants {
    public static final String TABLE_TITLE = "二号岗亭 停车信息";

    public static final String[] DAYS = {
        "星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日"
    };

    public static final String[] SHIFT_TYPES = {
        "白", "夜"
    };

    // 14 shift columns
    public static final String[] SHIFT_NAMES = {
        "周一 白", "周一 夜",
        "周二 白", "周二 夜",
        "周三 白", "周三 夜",
        "周四 白", "周四 夜",
        "周五 白", "周五 夜",
        "周六 白", "周六 夜",
        "周日 白", "周日 夜"
    };

    // Excel column letters for the 14 shifts (A:序号, B:姓名, C:车牌, D~Q:班次)
    public static final String[] EXCEL_COLUMNS = {
        "D", "E", "F", "G", "H", "I", "J", "K", "L", "M", "N", "O", "P", "Q"
    };

    // 第一页名单 (序号 1 ~ 39)
    public static final String[][] PAGE_1_ROSTER = {
        {"1", "刘文虎", "川F08CT9"},
        {"2", "郭旺平", "川H9756G"},
        {"3", "吴泽邦", "川HHV083"},
        {"4", "梁建军", "川J0053H"},
        {"5", "陈杰", "川R12Q86"},
        {"6", "伍丹", "川R7W951"},
        {"7", "杨俊", "川S7D100"},
        {"8", "高平", "鄂DR99M0"},
        {"9", "王飞", "鄂KU8165"},
        {"10", "李友福", "鄂QF96104"},
        {"11", "蓝福宝", "赣B99N15"},
        {"12", "邬细元", "赣J17K90"},
        {"13", "钟萍", "赣J2P297"},
        {"14", "邬辉萍", "赣J3Z161"},
        {"15", "李小勇", "贵C2370A"},
        {"16", "黄凯", "贵C3826V"},
        {"17", "蹇泽芳", "贵C38A52"},
        {"18", "周承鹏", "桂A298VR"},
        {"19", "卢至宝", "桂A2G3J0"},
        {"20", "陆树福", "桂A5G8S1"},
        {"21", "周明远", "桂A6J3L0"},
        {"22", "冯太河", "桂A8Y324"},
        {"23", "赵秋霞", "桂A8Y3Z6"},
        {"24", "何志锋", "桂AK780M"},
        {"25", "叶碧连", "桂AY76H3"},
        {"26", "韦金忠", "桂B718V9"},
        {"27", "麦秀军", "桂BDN795"},
        {"28", "张峻溶", "桂CA1073"},
        {"29", "欧志勇", "桂CR1496"},
        {"30", "徐皇兵", "桂CTB515"},
        {"31", "郑义深", "桂ES6959"},
        {"32", "王志宏", "桂FLF118"},
        {"33", "林慧东", "桂FVA610"},
        {"34", "叶观韩", "桂G16079"},
        {"35", "韦家力", "桂GEJ320"},
        {"36", "李建实", "桂GHJ830"},
        {"37", "韦家壮", "桂GM8157"},
        {"38", "莫泽生", "桂GR5035"},
        {"39", "严正生", "桂JXM679"}
    };

    // 第二页名单 (序号 40 ~ 77)
    public static final String[][] PAGE_2_ROSTER = {
        {"40", "钟业星", "桂KF5391"},
        {"41", "廖珍义", "桂MMT057"},
        {"42", "农勇", "桂R5D962"},
        {"43", "陈永金", "桂RBC235"},
        {"44", "陈汉生", "桂RDG257"},
        {"45", "兰燕虎", "桂RQZ237"},
        {"46", "汤健康", "桂RR1391"},
        {"47", "龙启武", "桂RWS716"},
        {"48", "孙鉴标", "桂RXF203"},
        {"49", "许登奎", "桂RZA803"},
        {"50", "张敬东", "辽HJF907"},
        {"51", "李启斌", "琼AKV222"},
        {"52", "刘朋辉", "陕D50Y06"},
        {"53", "谢欣展", "陕E9929H"},
        {"54", "许峰", "陕F1511G"},
        {"55", "许金成", "陕F3295Y"},
        {"56", "刘宏涛", "陕UYP567"},
        {"57", "郑辉峰", "湘D9N558"},
        {"58", "苏建民", "湘DBQ076"},
        {"59", "黄华林", "湘DDM935"},
        {"60", "黄阳", "湘DTC310"},
        {"61", "佘良君", "湘E103U2"},
        {"62", "涂家宝", "湘EVK225"},
        {"63", "卢俊", "湘H715EW"},
        {"64", "曾新宇", "湘HCZ761"},
        {"65", "彭杰", "湘HFY638"},
        {"66", "庄权", "湘HTF621"},
        {"67", "龚家军", "湘JLF650"},
        {"68", "刘连付", "湘L1CV19"},
        {"69", "邓志强", "湘L3220T"},
        {"70", "刘军", "湘L5G187"},
        {"71", "袁爱兵", "湘L8BX83"},
        {"72", "邓群晖", "湘LA992D"},
        {"73", "刘国亮", "湘LB865V"},
        {"74", "刘华", "湘LJ5620"},
        {"75", "王通", "湘LK2999"},
        {"76", "欧阳小华", "湘LP3529"},
        {"77", "刘积亮", "湘LQ8250"}
    };

    // 第三页名单 (序号 78 ~ 115)
    public static final String[][] PAGE_3_ROSTER = {
        {"78", "黄交华", "湘LR9977"},
        {"79", "李伟平", "湘Lu4079"},
        {"80", "宋运光", "湘LV8979"},
        {"81", "贤剑", "湘M2U612"},
        {"82", "张绍芳", "湘MNL710"},
        {"83", "杨东军", "湘MQ8011"},
        {"84", "李水林", "湘UJ2135"},
        {"85", "邓用应", "渝AB9Q52"},
        {"86", "邹碧用", "渝D030N5"},
        {"87", "黄什", "渝D84985"},
        {"88", "张发清", "渝F3W378"},
        {"89", "朱厚清", "渝FJ1618"},
        {"90", "袁社会", "豫AW6L32"},
        {"91", "陈朝轩", "豫ER3T83"},
        {"92", "武高伟", "豫P6307A"},
        {"93", "涂功清", "豫RP15N6"},
        {"94", "王娟娟", "豫SN5H36"},
        {"95", "张书晴", "粤A00WH4"},
        {"96", "吴梅珍", "粤A00YF5"},
        {"97", "郑金培", "粤A03G3Y"},
        {"98", "何大妹", "粤A08T1J"},
        {"99", "李军", "粤A0ZG87"},
        {"100", "蓝福顺", "粤A11R1M"},
        {"101", "钟莉仪", "粤A12NF3"},
        {"102", "杨娟燕", "粤A17VT5"},
        {"103", "江志勇", "粤A1A61C"},
        {"104", "马耀燊", "粤A1BP38"},
        {"105", "刘燕欢", "粤A1K8R3"},
        {"106", "颜桂浩", "粤A1N23S"},
        {"107", "庾泽培", "粤A1T10V"},
        {"108", "吴元平", "粤A1V02W"},
        {"109", "何双喜", "粤A1Y0A5"},
        {"110", "高镜坚", "粤A248WQ"},
        {"111", "张丽娜", "粤A28B5M"},
        {"112", "易志峰", "粤A28PV9"},
        {"113", "刘学广", "粤A29YK9"},
        {"114", "周鉴明", "粤A2DD39"},
        {"115", "刘健辉", "粤A2L53E"}
    };

    public static final String[][] DEFAULT_ROSTER = PAGE_1_ROSTER;

    public static List<ParkingRecord> createDefaultRecords() {
        return createRecordsForPage(1);
    }

    public static List<ParkingRecord> createRecordsForPage(int page) {
        String[][] source;
        if (page == 2) {
            source = PAGE_2_ROSTER;
        } else if (page == 3) {
            source = PAGE_3_ROSTER;
        } else {
            source = PAGE_1_ROSTER;
        }
        List<ParkingRecord> list = new ArrayList<>();
        for (String[] item : source) {
            int idx = Integer.parseInt(item[0]);
            list.add(new ParkingRecord(idx, item[1], item[2]));
        }
        return list;
    }

    public static List<ParkingRecord> createAllRecords() {
        List<ParkingRecord> list = new ArrayList<>();
        for (String[] item : PAGE_1_ROSTER) {
            int idx = Integer.parseInt(item[0]);
            list.add(new ParkingRecord(idx, item[1], item[2]));
        }
        for (String[] item : PAGE_2_ROSTER) {
            int idx = Integer.parseInt(item[0]);
            list.add(new ParkingRecord(idx, item[1], item[2]));
        }
        for (String[] item : PAGE_3_ROSTER) {
            int idx = Integer.parseInt(item[0]);
            list.add(new ParkingRecord(idx, item[1], item[2]));
        }
        return list;
    }
}
