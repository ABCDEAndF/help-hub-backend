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

        assertThat(answer).contains("免费领取", "应急生活包", "18包", "夏阳公益服务点", "物资预约")
            .doesNotContain("未配置");
    }

    @Test
    void rendersResidentRequestStatusInPlainChinese() {
        var request = new SupplyRequestView(17, 3, "居民", "FOOD", null, "大米", 2, Urgency.NORMAL,
            FulfillmentMethod.PICKUP, RequestStatus.APPROVED, BigDecimal.ONE, BigDecimal.ONE, "青浦", null, null, null, null,
            1L, null, "邻需通·夏阳公益服务点", null, Instant.EPOCH, Instant.EPOCH);

        assertThat(AssistantService.describeRequest(request))
            .isEqualTo("申请 #17：大米，数量 2，当前状态：已批准，可以预约物资。领取方式：到点自取（邻需通·夏阳公益服务点）。");
        assertThat(AssistantService.describeRequests(List.of(request))).contains("您最近的申请", "申请 #17");
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
    void mapsColloquialCategories() {
        assertThat(AssistantService.matchedCategory("有什么吃的")).isEqualTo("FOOD");
        assertThat(AssistantService.matchedItemTerm("应急物资有吗")).isEqualTo("应急");
        assertThat(AssistantToolExecutor.INVENTORY_CATEGORIES).contains("EMERGENCY");
    }

    @Test
    void listsReservationsWithPickupCodeAndReservationNumber() {
        var reservation = new ReservationService.ReservationView(5, 17, "公益大米 5 千克", 1, "HELD",
            Instant.parse("2026-09-28T04:00:00Z"), Instant.EPOCH, "邻需通·夏阳公益服务点", "青松路", 0, 0,
            null, null, "123456");

        assertThat(AssistantService.describeReservations(List.of(reservation)))
            .contains("预约 #5", "领取码 123456", "9月28日 12:00", "邻需通·夏阳公益服务点");
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
