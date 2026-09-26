package org.isolatedareas.helphub.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PinyinIndexTest {
    // Every item name stocked in production, with the letter a resident would look under.
    private static final Map<String, String> EXPECTED = Map.ofEntries(
        Map.entry("公益大米 5 千克", "D"), Map.entry("公益饮用水 12 瓶装", "Y"), Map.entry("公益基础卫生用品包", "J"),
        Map.entry("公益应急生活包", "Y"), Map.entry("公益挂面 1 千克", "G"), Map.entry("公益食用油 1 升", "S"),
        Map.entry("抽纸 10 包公益装", "C"), Map.entry("基础急救用品包", "J"), Map.entry("应急手电筒与电池包", "Y"),
        Map.entry("常温食品综合包", "C"), Map.entry("婴幼儿基础护理包", "Y"), Map.entry("基础医疗用品包", "J"),
        Map.entry("公益面粉 5 千克", "M"), Map.entry("公益纯牛奶 250 毫升×12 盒", "C"), Map.entry("公益方便面 5 连包", "F"),
        Map.entry("公益燕麦片 1 千克", "Y"), Map.entry("公益食盐 400 克", "S"), Map.entry("公益酱油 500 毫升", "J"),
        Map.entry("公益午餐肉罐头 340 克", "W"), Map.entry("公益苏打饼干 1 千克", "S"),
        Map.entry("公益饮用水 550 毫升×24 瓶", "Y"), Map.entry("洗衣液 2 千克", "X"), Map.entry("香皂 3 块装", "X"),
        Map.entry("牙膏牙刷套装", "Y"), Map.entry("卫生巾日夜组合装", "W"), Map.entry("成人纸尿裤 L 码", "C"),
        Map.entry("婴儿纸尿裤 M 码", "Y"), Map.entry("医用外科口罩 50 只", "Y"), Map.entry("电子体温计", "D"),
        Map.entry("酒精消毒液 75% 500 毫升", "J"), Map.entry("保暖毯", "B"), Map.entry("一次性雨衣", "Y"),
        Map.entry("应急蜡烛与打火机套装", "Y"));

    @Test
    void everyStockedItemFilesUnderTheLetterAResidentWouldExpect() {
        EXPECTED.forEach((name, letter) -> assertThat(PinyinIndex.initial(name)).as(name).isEqualTo(letter));
    }

    @Test
    void ordersByPinyinIgnoringTheCharityPrefix() {
        List<String> names = new ArrayList<>(List.of("洗衣液 2 千克", "公益大米 5 千克", "保暖毯", "公益挂面 1 千克", "抽纸 10 包公益装"));
        names.sort(PinyinIndex.ORDER);
        assertThat(names).containsExactly("保暖毯", "抽纸 10 包公益装", "公益大米 5 千克", "公益挂面 1 千克", "洗衣液 2 千克");
    }
}
