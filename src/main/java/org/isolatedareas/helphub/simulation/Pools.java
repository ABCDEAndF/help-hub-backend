package org.isolatedareas.helphub.simulation;

import java.util.List;
import java.util.Map;

/** What residents write: assistant questions, delivery notes, needs outside the stock, feedback. */
final class Pools {
    private Pools() {
    }

    /** "{n}" is replaced by the resident's latest request number. */
    static final Map<DailyActivityPlanner.ChatTopic, List<String>> QUESTIONS = Map.of(
        DailyActivityPlanner.ChatTopic.INVENTORY, List.of(
            "现在有哪些物资？", "有大米吗？", "饮用水还有吗", "有没有食用油", "卫生用品有吗",
            "挂面还有库存吗", "急救包还有吗", "最近有什么物资可以领"),
        DailyActivityPlanner.ChatTopic.SERVICE_POINTS, List.of(
            "服务点几点开门？", "青浦有哪些服务点", "夏阳的服务点在哪", "服务点地址在哪里",
            "工业园区那个服务点几点关门", "最近的领取点在哪", "服务点营业时间是几点到几点", "服务点在哪"),
        DailyActivityPlanner.ChatTopic.PROGRESS, List.of(
            "我的申请进度", "我的申请到哪一步了", "{n}号申请到哪了", "帮我查一下{n}号单子",
            "我的订单状态", "申请 #{n} 现在什么状态", "我提交的申请怎么样了", "{n}号订单进度"),
        DailyActivityPlanner.ChatTopic.RESERVATIONS, List.of(
            "我的领取码", "我的预约记录", "领取码在哪里看", "我的预约状态",
            "我预约了什么", "我的领取码是多少", "查一下我的预约", "我的预约记录有哪些"),
        DailyActivityPlanner.ChatTopic.HOW_TO, List.of(
            "怎么申请和领取？", "如何申请配送", "怎么预约自取", "怎样提交申请",
            "如何领取物资", "配送怎么申请", "领物资的流程是什么", "怎么用这个小程序申请"));

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
