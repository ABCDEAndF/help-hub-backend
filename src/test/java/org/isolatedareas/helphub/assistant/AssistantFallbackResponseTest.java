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
    void answersInTheLanguageTheResidentWroteInWhateverTheInterfaceShows() {
        assertThat(AssistantService.replyLanguage("help me set my location", "zh")).isEqualTo("en");
        assertThat(AssistantService.replyLanguage("有大米吗", "en")).isEqualTo("zh");
        assertThat(AssistantService.replyLanguage("I want 大米", "zh")).isEqualTo("en");
        assertThat(AssistantService.replyLanguage("我想要 masks", "en")).isEqualTo("zh");
        // Nothing to tell by: the interface decides.
        assertThat(AssistantService.replyLanguage("#3", "en")).isEqualTo("en");
        assertThat(AssistantService.replyLanguage("3", null)).isEqualTo("zh");
    }

    @Test
    void readsAPlainYesOrNoToTheWaitingActionAndLeavesAnythingElseToTheModel() {
        for (String yes : List.of("确认", "好的", "好的，谢谢", "嗯嗯", "是的确认", "可以", "OK", "okay!", "Yes please", "sure", "确认执行", "行吧")) {
            assertThat(AssistantService.answerTo(yes, "prepare_supply_request")).as(yes).isEqualTo(AssistantService.Answer.YES);
        }
        for (String no : List.of("不要", "不用了", "算了", "no", "nope", "No thanks", "先不要", "取消")) {
            assertThat(AssistantService.answerTo(no, "prepare_supply_request")).as(no).isEqualTo(AssistantService.Answer.NO);
        }
        for (String other : List.of("改成3袋", "好不好", "不确认", "可以送到楼下吗", "what time do they open", "yes but 2 bags", "")) {
            assertThat(AssistantService.answerTo(other, "prepare_supply_request")).as(other).isEqualTo(AssistantService.Answer.UNSURE);
        }
        // Answering a cancellation with "取消" could mean either: the model reads it in context.
        assertThat(AssistantService.answerTo("取消", "cancel_my_request")).isEqualTo(AssistantService.Answer.UNSURE);
        assertThat(AssistantService.answerTo("确认取消", "cancel_my_request")).isEqualTo(AssistantService.Answer.UNSURE);
        assertThat(AssistantService.answerTo("确认", "cancel_my_request")).isEqualTo(AssistantService.Answer.YES);
    }

    @Test
    void explainsWhyAnAgreedActionFailedInTheResidentsWords() {
        assertThat(AssistantService.reason(new IllegalArgumentException("Confirmation expired or was already used"))).contains("5 分钟");
        assertThat(AssistantService.reason(new org.springframework.web.server.ResponseStatusException(
            org.springframework.http.HttpStatus.CONFLICT, org.isolatedareas.helphub.requests.RequestService.NOT_CANCELLABLE)))
            .contains("只有待审核的申请可以自己取消");
        assertThat(AssistantService.reason(new org.springframework.web.server.ResponseStatusException(
            org.springframework.http.HttpStatus.CONFLICT, "Feedback already given"))).contains("已经评价过了");
        assertThat(AssistantService.reason(new IllegalStateException("boom"))).isEqualTo("请稍后再试。");
    }

    @Test
    void saysWhereTheDeliveryIsOrWhyThereIsNone() {
        var waiting = new SupplyRequestView(17, 3, 2, "居民", "FOOD", null, "大米", 2, Urgency.NORMAL,
            FulfillmentMethod.DELIVERY, RequestStatus.SUBMITTED, BigDecimal.ONE, BigDecimal.ONE, "青浦", null, null, null, null,
            null, null, null, null, Instant.EPOCH, Instant.EPOCH);
        assertThat(AssistantService.describeDelivery(new AssistantToolExecutor.Delivery(waiting, null)))
            .isEqualTo("申请 #2 还在等待审核，批准后系统才会安排补给车。");
        var trip = new org.isolatedareas.helphub.automation.CartTripService.Tracking(1, "青浦公益补给车一号", 31.1, 121.1,
            "配送途中", Instant.parse("2026-10-11T03:20:00Z"), false, 31.2, 121.2, List.of());
        assertThat(AssistantService.describeDelivery(new AssistantToolExecutor.Delivery(waiting, trip)))
            .isEqualTo("申请 #2：青浦公益补给车一号，配送途中，预计 11:20 送达。可在地图页查看车辆位置。");
        assertThat(AssistantService.describeDelivery(new AssistantToolExecutor.Delivery(null, null))).contains("还没有选择补给车配送");
    }

    @Test
    void answersQuestionsAboutNoticesWithoutAModel() {
        var executor = org.mockito.Mockito.mock(AssistantToolExecutor.class);
        org.mockito.Mockito.when(executor.execute(org.mockito.ArgumentMatchers.eq(1L),
                org.mockito.ArgumentMatchers.eq("list_my_notifications"), org.mockito.ArgumentMatchers.any()))
            .thenReturn(new AssistantToolExecutor.ToolResult(List.of(), null));
        var service = new AssistantService(null, executor, null, null, new com.fasterxml.jackson.databind.ObjectMapper(), 4, "18321961090");
        assertThat(service.lookupForTest(1L, "有没有新消息")).isEqualTo("您还没有收到任何通知。");
    }

    @Test
    void listsNoticesByTheNamesTheNoticesPageUses() {
        var payload = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode().put("requestNumber", 3);
        var notice = new AssistantToolExecutor.Notice("REQUEST_APPROVED", payload, Instant.parse("2026-10-11T01:30:00Z"));
        assertThat(AssistantService.describeNotices(List.of(notice)))
            .startsWith("您最近的通知：\n• 10月11日 09:30 需求已批准，可预约物资（申请 #3）\n");
        assertThat(AssistantService.describeNotices(List.of())).isEqualTo("您还没有收到任何通知。");
    }

    @Test
    void treatsOnlyClearDangerAsAnEmergency() {
        assertThat(AssistantService.inDanger("救命！我奶奶晕倒了")).isTrue();
        assertThat(AssistantService.inDanger("家里着火了")).isTrue();
        assertThat(AssistantService.inDanger("my grandmother fainted, help me")).isTrue();
        assertThat(AssistantService.inDanger("please call 120")).isTrue();
        // Asking for help with the app, or for a first aid kit, is not an emergency.
        assertThat(AssistantService.inDanger("help me set my location")).isFalse();
        assertThat(AssistantService.inDanger("我需要急救包")).isFalse();
        assertThat(AssistantService.inDanger("I need 120 masks")).isFalse();
        assertThat(AssistantService.inDanger("帮我申请两袋大米")).isFalse();
    }

    @Test
    void keepsTheEmergencyMappingOnlyForRealDanger() {
        var setLocation = new AssistantModels.ChatRequest(null, "help me set locations", null, null, null, null, "en", "救命", null);
        var fainted = new AssistantModels.ChatRequest(null, "my grandmother fainted, help me", null, null, null, null, "en", "救命", null);
        var chinese = new AssistantModels.ChatRequest(null, "救命", null, null, null, null, null, null, null);
        assertThat(AssistantService.lookupText(setLocation)).isEqualTo("help me set locations");
        assertThat(AssistantService.lookupText(fainted)).isEqualTo("救命");
        assertThat(AssistantService.lookupText(chinese)).isEqualTo("救命");
    }

    @Test
    void answersTheItemsAskedForWhetherStockedReservedOrMissing() {
        var rice = new InventoryItemView(1, 1, "邻需通·夏阳公益服务点", "青松路", "QP-XY-RICE-5KG",
            "公益大米 5 千克", "FOOD", "袋", 0, 80, 0, 80, 15, 0, Instant.EPOCH);
        var mask = new InventoryItemView(2, 1, "邻需通·夏阳公益服务点", "青松路", "QP-XY-MASK-50",
            "医用外科口罩 50 只", "MEDICAL", "盒", 0, 14, 14, 0, 5, 0, Instant.EPOCH);
        String answer = AssistantService.describeAskedItems(List.of(rice, mask), List.of("大米", "口罩", "助行器"));
        assertThat(answer.split("\n\n")).hasSize(3);
        assertThat(answer).contains("公益大米 5 千克：80袋（邻需通·夏阳公益服务点）", "医用外科口罩 50 只 目前已全部被预约",
            "没有名称包含“助行器”");
        // Asked only for masks: no rice in the answer.
        assertThat(AssistantService.describeAskedItems(List.of(rice, mask), List.of("口罩"))).doesNotContain("大米");
    }

    @Test
    void listsTheNearestPointsWithDistanceAndHours() {
        var point = new LinkedHashMap<String, Object>();
        point.put("name", "邻需通·夏阳公益服务点");
        point.put("address", "上海市青浦区青松路245号附近");
        point.put("distanceMeters", 1234L);
        point.put("opensAt", java.time.LocalTime.of(8, 30));
        point.put("closesAt", java.time.LocalTime.of(16, 30));
        assertThat(AssistantService.describeNearby(List.of(point)))
            .startsWith("离您最近的服务点：\n• 邻需通·夏阳公益服务点：上海市青浦区青松路245号附近，约 1.2 公里，营业时间 08:30–16:30");
        assertThat(AssistantService.describeNearby(List.of())).contains("10 公里内没有服务点");
    }

    @Test
    void removesMarkdownTheMiniProgramWouldShowAsIs() {
        assertThat(AssistantService.plainText("## 服务\n1. **Supply Requests**: use `求助`"))
            .isEqualTo("服务\n1. Supply Requests: use 求助");
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
