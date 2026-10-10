package org.isolatedareas.helphub.simulation;

import java.util.List;
import java.util.Map;

/** What residents write: delivery notes, needs outside the stock, feedback. */
final class Pools {
    private Pools() {
    }

    static final List<String> NOTES = List.of(
        "家中有行动不便的老人，请敲门后稍等", "到了请打电话，我下楼拿", "白天家里没人，傍晚后方便",
        "楼道没有电梯，辛苦了", "请放在门口就好", "从小区东门进来比较近", "家里有小孩在睡觉，请轻敲门",
        "腿脚不方便，麻烦送到楼上");

    /** Needs that are not in stock: category and description. */
    static final List<String[]> MANUAL_NEEDS = List.of(
        new String[] {"OTHER", "老人用的助行器"}, new String[] {"MEDICAL", "电子血压计"},
        new String[] {"OTHER", "儿童冬季棉衣"}, new String[] {"HYGIENE", "成人纸尿裤"},
        new String[] {"FOOD", "无糖食品，家里有糖尿病老人"}, new String[] {"OTHER", "手机充电宝"},
        new String[] {"MEDICAL", "医用口罩一盒"}, new String[] {"OTHER", "厚棉被一床"},
        new String[] {"FOOD", "婴儿米粉"}, new String[] {"HYGIENE", "洗衣液"},
        new String[] {"OTHER", "取暖用的电热毯"}, new String[] {"MEDICAL", "体温计"});

    static final Map<Integer, List<String>> FEEDBACK = Map.of(
        5, List.of("很方便，谢谢工作人员", "送得很快，东西也齐全", "帮了大忙，感谢", "服务态度很好",
            "比自己去超市方便多了", "老人在家也能领到，很好"),
        4, List.of("挺好的，就是等了一会儿", "整体不错", "东西收到了，谢谢", "希望物资种类再多一些"),
        3, List.of("还可以，送得有点慢", "领取点有点远", "一般，希望能改进"),
        2, List.of("等了很久才送到", "想要的物资经常没有"),
        1, List.of("这次体验不太好"));
}
