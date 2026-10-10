package org.isolatedareas.helphub.assistant;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import org.isolatedareas.helphub.domain.FulfillmentMethod;
import org.isolatedareas.helphub.domain.RequestStatus;
import org.isolatedareas.helphub.domain.Urgency;
import org.isolatedareas.helphub.inventory.InventoryItemView;
import org.isolatedareas.helphub.inventory.ReservationService;
import org.isolatedareas.helphub.requests.SupplyRequestView;
import org.junit.jupiter.api.Test;

class AssistantFallbackResponseTest {

    @Test
    void rendersRealInventoryInsteadOfConfigurationMessage() {
        var item = new InventoryItemView(1, 1, "夏阳公益服务点", "青松路245号附近", "QP-1",
            "应急生活包", "OTHER", "包", 0, 20, 2, 18, 5, 0, Instant.EPOCH);

        String answer = AssistantService.describeInventory(List.of(item));

        assertThat(answer).contains("免费领取", "应急生活包", "18包", "夏阳公益服务点", "工作人员审核")
            .doesNotContain("未配置");
    }

    @Test
    void rendersResidentRequestStatusInPlainChinese() {
        var request = new SupplyRequestView(17, 3, 2, "居民", "FOOD", null, "大米", 2, Urgency.NORMAL,
            FulfillmentMethod.PICKUP, RequestStatus.APPROVED, BigDecimal.ONE, BigDecimal.ONE, "青浦", null, null, null, null,
            1L, null, "邻需通·夏阳公益服务点", null, Instant.EPOCH, Instant.EPOCH);

        assertThat(AssistantService.describeRequest(request))
            .isEqualTo("申请 #2：大米，数量 2，当前状态：已批准，可以预约物资。领取方式：到点自取（邻需通·夏阳公益服务点）。");
        assertThat(AssistantService.describeRequests(List.of(request))).contains("您最近的申请", "申请 #2").doesNotContain("#17");
        assertThat(AssistantService.describeRequests(List.of())).contains("还没有提交过申请");
    }

    @Test
    void answersQuestionsAboutOneItemWithEveryPointThatStocksIt() {
        var xiayang = new InventoryItemView(1, 1, "邻需通·夏阳公益服务点", "青松路", "QP-XY-RICE-5KG",
            "公益大米 5 千克", "FOOD", "袋", 0, 80, 0, 80, 15, 0, Instant.EPOCH);
        var industrial = new InventoryItemView(2, 2, "邻需通·青浦工业园区公益服务点", "清河湾路", "QP-GY-RICE-5KG",
            "公益大米 5 千克", "FOOD", "袋", 0, 100, 0, 100, 15, 0, Instant.EPOCH);
        var noodles = new InventoryItemView(3, 1, "邻需通·夏阳公益服务点", "青松路", "QP-XY-NOODLES-1KG",
            "公益挂面 1 千克", "FOOD", "包", 0, 70, 0, 70, 10, 0, Instant.EPOCH);

        assertThat(AssistantService.matchedItemTerm("有大米吗")).isEqualTo("大米");
        String answer = AssistantService.describeInventory(List.of(xiayang, industrial, noodles), "大米");
        assertThat(answer).contains("80袋（邻需通·夏阳公益服务点）", "100袋（邻需通·青浦工业园区公益服务点）")
            .doesNotContain("挂面");
        assertThat(AssistantService.describeInventory(List.of(noodles), "大米")).contains("没有名称包含“大米”");
    }

    @Test
    void findsItemsByTheNamesResidentsSee() {
        var mask = new InventoryItemView(1, 1, "邻需通·夏阳公益服务点", "青松路", "QP-XY-MASK-50",
            "医用外科口罩 50 只", "MEDICAL", "盒", 0, 14, 0, 14, 5, 0, Instant.EPOCH);
        var milk = new InventoryItemView(2, 1, "邻需通·夏阳公益服务点", "青松路", "QP-XY-MILK-12",
            "公益纯牛奶 250 毫升×12 盒", "FOOD", "箱", 0, 26, 0, 26, 5, 0, Instant.EPOCH);
        var adult = new InventoryItemView(3, 2, "邻需通·青浦工业园区公益服务点", "清河湾路", "QP-GY-ADULT-DIAPER-L",
            "成人纸尿裤 L 码", "HYGIENE", "包", 0, 14, 0, 14, 5, 0, Instant.EPOCH);
        var baby = new InventoryItemView(4, 1, "邻需通·夏阳公益服务点", "青松路", "QP-XY-BABY-DIAPER-M",
            "婴儿纸尿裤 M 码", "HYGIENE", "包", 0, 13, 0, 13, 5, 0, Instant.EPOCH);
        var tissue = new InventoryItemView(5, 1, "邻需通·夏阳公益服务点", "青松路", "QP-XY-TISSUE-10",
            "抽纸 10 包公益装", "HYGIENE", "提", 0, 29, 0, 29, 5, 0, Instant.EPOCH);
        var blanket = new InventoryItemView(6, 2, "邻需通·青浦工业园区公益服务点", "清河湾路", "QP-GY-BLANKET",
            "保暖毯", "OTHER", "条", 0, 9, 9, 0, 5, 0, Instant.EPOCH);
        var kit = new InventoryItemView(7, 2, "邻需通·青浦工业园区公益服务点", "清河湾路", "QP-GY-HYGIENE-01",
            "公益基础卫生用品包", "HYGIENE", "包", 0, 32, 0, 32, 5, 0, Instant.EPOCH);
        var stock = List.of(mask, milk, adult, baby, tissue, blanket, kit);

        assertThat(AssistantService.coreName("公益纯牛奶 250 毫升×12 盒")).isEqualTo("纯牛奶");
        assertThat(AssistantService.matchedItems(stock, "有口罩吗")).containsExactly(mask);
        assertThat(AssistantService.matchedItems(stock, "还有牛奶吗？")).containsExactly(milk);
        // The longest shared run wins: diapers, not tissues.
        assertThat(AssistantService.matchedItems(stock, "有纸尿裤吗")).containsExactly(adult, baby);
        assertThat(AssistantService.matchedItems(stock, "我的申请进度")).isEmpty();
        assertThat(AssistantService.matchedItems(stock, "有成人纸尿裤、纯牛奶吗")).containsExactly(milk, adult);
        assertThat(AssistantService.matchedItems(stock, "有什么用品")).isEmpty();
        assertThat(AssistantService.describeNamedInventory(AssistantService.matchedItems(stock, "有保暖毯吗")))
            .contains("保暖毯 目前已全部被预约");
        assertThat(AssistantService.describeNamedInventory(List.of(adult, baby)))
            .contains("成人纸尿裤 L 码：14包（邻需通·青浦工业园区公益服务点）", "婴儿纸尿裤 M 码：13包");
    }

    @Test
    void listsEveryProductOnceWithEachPointThatStocksIt() {
        var xiayang = new InventoryItemView(1, 1, "邻需通·夏阳公益服务点", "青松路", "QP-XY-RICE-5KG",
            "公益大米 5 千克", "FOOD", "袋", 0, 80, 0, 80, 15, 0, Instant.EPOCH);
        var industrial = new InventoryItemView(2, 2, "邻需通·青浦工业园区公益服务点", "清河湾路", "QP-GY-RICE-5KG",
            "公益大米 5 千克", "FOOD", "袋", 0, 100, 0, 100, 15, 0, Instant.EPOCH);
        var many = new java.util.ArrayList<InventoryItemView>(List.of(xiayang, industrial));
        for (int i = 0; i < 30; i++) {
            many.add(new InventoryItemView(10 + i, 1, "邻需通·夏阳公益服务点", "青松路", "QP-" + i,
                "物资" + i, "OTHER", "件", 0, 5, 0, 5, 1, 0, Instant.EPOCH));
        }
        String answer = AssistantService.describeInventory(many);
        assertThat(answer).contains("• 公益大米 5 千克：80袋（邻需通·夏阳公益服务点）、100袋（邻需通·青浦工业园区公益服务点）", "物资29：5件");
    }

    @Test
    void tellsHowToQuestionsFromQuestionsAboutOnesOwnRequests() {
        assertThat(AssistantService.asksHowTo("怎么申请和领取？")).isTrue();
        assertThat(AssistantService.asksHowTo("如何申请配送")).isTrue();
        assertThat(AssistantService.asksHowTo("我的申请进度怎么样了")).isFalse();
        assertThat(AssistantService.asksHowTo("我的申请怎么样了")).isFalse();
        assertThat(AssistantService.asksHowTo("申请到哪一步了，怎么还没批")).isFalse();
    }

    @Test
    void leavesRequestsToHaveSomethingDoneToTheModel() {
        assertThat(AssistantService.asksForAction("帮我申请两袋大米，送到家")).isTrue();
        assertThat(AssistantService.asksForAction("我要预约领取")).isTrue();
        assertThat(AssistantService.asksForAction("还有大米吗？")).isFalse();
        assertThat(AssistantService.asksForAction("我想知道我的领取码")).isFalse();
        assertThat(AssistantService.asksForAction("服务点几点开门")).isFalse();
    }

    @Test
    void mapsColloquialCategories() {
        assertThat(AssistantService.matchedCategory("有什么吃的")).isEqualTo("FOOD");
        assertThat(AssistantService.matchedItemTerm("应急物资有吗")).isEqualTo("应急");
        assertThat(AssistantToolExecutor.INVENTORY_CATEGORIES).contains("EMERGENCY");
    }

    @Test
    void listsReservationsWithPickupCodeAndReservationNumber() {
        var reservation = new ReservationService.ReservationView(5, 17, "公益大米 5 千克", 1, "HELD",
            Instant.parse("2026-09-28T04:00:00Z"), Instant.EPOCH, "邻需通·夏阳公益服务点", "青松路", 0, 0,
            null, null, "123456", 3);

        assertThat(AssistantService.describeReservations(List.of(reservation)))
            .contains("申请 #3", "领取码 123456", "9月28日 12:00", "邻需通·夏阳公益服务点");
    }

    @Test
    void rendersServicePointNamesAndAddresses() {
        var point = new LinkedHashMap<String, Object>();
        point.put("name", "邻需通·夏阳公益服务点");
        point.put("address", "上海市青浦区青松路245号附近");

        point.put("opensAt", java.time.LocalTime.of(8, 30));
        point.put("closesAt", java.time.LocalTime.of(16, 30));

        assertThat(AssistantService.describeServicePoints(List.of(point)))
            .contains("邻需通·夏阳公益服务点", "上海市青浦区青松路245号附近", "营业时间 08:30–16:30", "首页地图");
    }
}
