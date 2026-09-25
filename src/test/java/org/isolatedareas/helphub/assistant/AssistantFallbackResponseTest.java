package org.isolatedareas.helphub.assistant;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import org.isolatedareas.helphub.domain.RequestStatus;
import org.isolatedareas.helphub.domain.Urgency;
import org.isolatedareas.helphub.inventory.InventoryItemView;
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
        var request = new SupplyRequestView(17, 3, "居民", "FOOD", "大米", 2, Urgency.NORMAL,
            RequestStatus.APPROVED, BigDecimal.ONE, BigDecimal.ONE, "青浦", null, null, null,
            1L, null, Instant.EPOCH, Instant.EPOCH);

        assertThat(AssistantService.describeRequest(request))
            .isEqualTo("申请 #17：大米，数量 2，当前状态：已批准，可以预约物资。");
    }

    @Test
    void rendersServicePointNamesAndAddresses() {
        var point = new LinkedHashMap<String, Object>();
        point.put("name", "邻需通·夏阳公益服务点");
        point.put("address", "上海市青浦区青松路245号附近");

        assertThat(AssistantService.describeServicePoints(List.of(point)))
            .contains("邻需通·夏阳公益服务点", "上海市青浦区青松路245号附近", "首页地图");
    }
}
