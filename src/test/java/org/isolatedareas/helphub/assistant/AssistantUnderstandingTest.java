package org.isolatedareas.helphub.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import org.isolatedareas.helphub.inventory.InventoryItemView;
import org.junit.jupiter.api.Test;

class AssistantUnderstandingTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static JsonNode read(String text) {
        try {
            return JSON.readTree(text);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void mapsEachIntentToTheHandlerThatDoesIt() {
        var stock = AssistantService.step(read("{\"intent\":\"stock\",\"items\":[\"大米\"]}"), JSON);
        assertThat(stock.tool()).isEqualTo("check_inventory");
        assertThat(stock.args().path("items").get(0).asText()).isEqualTo("大米");

        // Numbers come as 2, "2", "#2" or "2号".
        var apply = AssistantService.step(read("{\"intent\":\"apply\",\"item\":\"大米\",\"quantity\":\"2袋\",\"method\":\"PICKUP\"}"), JSON);
        assertThat(apply.tool()).isEqualTo("prepare_supply_request");
        assertThat(apply.args().path("itemDescription").asText()).isEqualTo("大米");
        assertThat(apply.args().path("quantity").asInt()).isEqualTo(2);
        assertThat(apply.args().path("fulfillmentMethod").asText()).isEqualTo("PICKUP");

        assertThat(AssistantService.step(read("{\"intent\":\"cancel_request\",\"requestNumber\":\"#3\"}"), JSON).args()
            .path("requestId").asInt()).isEqualTo(3);
        assertThat(AssistantService.step(read("{\"intent\":\"set_location\",\"address\":\"青浦区青湖路88弄\"}"), JSON).tool())
            .isEqualTo("search_address");
        assertThat(AssistantService.step(read("{\"intent\":\"how_to\",\"topic\":\"cancel_request\"}"), JSON).args()
            .path("topic").asText()).isEqualTo("cancel");
        assertThat(AssistantService.step(read("{\"intent\":\"chat\"}"), JSON)).isNull();
        assertThat(AssistantService.step(read("{\"intent\":\"order_pizza\"}"), JSON)).isNull();
        // A "null" written out by the model is no value.
        assertThat(AssistantService.step(read("{\"intent\":\"navigate\",\"keyword\":\"null\"}"), JSON).args().has("keyword")).isFalse();
    }

    @Test
    void everyHandlerAnIntentNamesIsOneTheServiceHas() {
        for (String intent : List.of("stock", "service_points", "nearest_points", "my_requests", "request_status", "my_codes",
            "my_notifications", "my_profile", "track_delivery", "how_to", "apply", "cancel_request", "reserve", "feedback",
            "update_profile", "set_location", "choose_place", "set_language", "call_hotline", "navigate", "open_page")) {
            String tool = AssistantService.step(read("{\"intent\":\"" + intent + "\"}"), JSON).tool();
            assertThat(AssistantService.READ_TOOLS.contains(tool) || AssistantService.ACTION_TOOLS.contains(tool)
                || AssistantService.DIRECT_TOOLS.contains(tool)).as(intent + " -> " + tool).isTrue();
        }
    }

    @Test
    void turnsADateAndHalfDayIntoTheRequestWindow() {
        JsonNode args = AssistantService.withWindow("prepare_supply_request",
            read("{\"itemDescription\":\"大米\",\"date\":\"2026-10-12\",\"slot\":\"AFTERNOON\"}"));
        assertThat(args.path("preferredStart").asText()).isEqualTo("2026-10-12T13:00+08:00");
        assertThat(args.path("preferredEnd").asText()).isEqualTo("2026-10-12T17:00+08:00");
        assertThat(args.has("date")).isFalse();
        // No time given: as soon as possible.
        assertThat(AssistantService.withWindow("prepare_supply_request", read("{\"itemDescription\":\"大米\"}")).has("preferredStart")).isFalse();
        assertThatThrownBy(() -> AssistantService.withWindow("prepare_supply_request", read("{\"date\":\"2026-10-12\"}")))
            .hasMessageContaining("上午");
    }

    @Test
    void readsTheModelsJsonAndAsksAgainWhenItClaimsToHaveDoneSomething() {
        var service = new AssistantService(null, null, null, null, JSON, 4, "18321961090");
        assertThat(service.parseUnderstanding("```json\n{\"actions\":[{\"intent\":\"stock\"}]}\n```").path("actions").size()).isEqualTo(1);
        assertThat(service.parseUnderstanding("Please confirm: quantity 4")).isNull();
        assertThat(AssistantService.problemWith(null, "x")).contains("JSON");
        assertThat(AssistantService.problemWith(read("{\"actions\":[],\"reply\":\"好的，界面语言已切换为中文。\"}"), "x")).contains("intent");
        assertThat(AssistantService.problemWith(read("{\"actions\":[],\"reply\":\"Got it, your household size is set to 3.\"}"), "x")).contains("intent");
        assertThat(AssistantService.problemWith(read("{\"actions\":[],\"reply\":\"Switched to English.\"}"), "x")).contains("intent");
        assertThat(AssistantService.problemWith(read("{\"actions\":[{\"intent\":\"set_language\",\"language\":\"zh\"}]}"), "x")).isNull();
        assertThat(AssistantService.problemWith(read("{\"actions\":[],\"reply\":\"好的，要几袋？\"}"), "x")).isNull();
    }

    @Test
    void dropsANameOrHouseholdTheResidentNeverSaid() {
        var invented = AssistantService.step(read("{\"intent\":\"update_profile\",\"displayName\":\"张姐\"}"), JSON);
        assertThat(AssistantService.withoutInventedName(invented, "I want to update my address").args().has("displayName")).isFalse();
        var said = AssistantService.step(read("{\"intent\":\"update_profile\",\"displayName\":\"王阿姨\"}"), JSON);
        assertThat(AssistantService.withoutInventedName(said, "把我的称呼改成王阿姨").args().path("displayName").asText()).isEqualTo("王阿姨");
        var tom = AssistantService.step(read("{\"intent\":\"update_profile\",\"displayName\":\"Tom\"}"), JSON);
        assertThat(AssistantService.withoutInventedName(tom, "change my name to tom").args().has("displayName")).isTrue();
        var family = AssistantService.step(read("{\"intent\":\"update_profile\",\"householdSize\":4}"), JSON);
        assertThat(AssistantService.withoutInventedName(family, "我家一共4口人").args().path("householdSize").asInt()).isEqualTo(4);
        var noNumber = AssistantService.step(read("{\"intent\":\"update_profile\",\"householdSize\":4}"), JSON);
        assertThat(AssistantService.withoutInventedName(noNumber, "改一下我的资料").args().has("householdSize")).isFalse();
    }

    @Test
    void asksAgainWhenTheReplyOnlySaysBackWhatWasAsked() {
        assertThat(AssistantService.echoes("您有新消息吗？", "有没有新消息")).isTrue();
        assertThat(AssistantService.echoes("您有什么新通知吗？", "我有什么新通知吗")).isTrue();
        assertThat(AssistantService.echoes("好的，要几袋？到服务点自取还是补给车配送？", "我想申请点大米")).isFalse();
        assertThat(AssistantService.problemWith(read("{\"actions\":[],\"reply\":\"您有新消息吗？\"}"), "有没有新消息")).contains("重复");
    }

    @Test
    void findsStockByTheWordsResidentsUseForIt() {
        var tissue = new InventoryItemView(1, 1, "点", "址", "S1", "抽纸 10 包公益装", "HYGIENE", "提", 0, 9, 0, 9, 1, 0, Instant.EPOCH);
        var water = new InventoryItemView(2, 1, "点", "址", "S2", "公益饮用水 550 毫升×24 瓶", "WATER", "箱", 0, 9, 0, 9, 1, 0, Instant.EPOCH);
        var rice = new InventoryItemView(3, 1, "点", "址", "S3", "公益大米 5 千克", "FOOD", "袋", 0, 9, 0, 9, 1, 0, Instant.EPOCH);
        var stock = List.of(tissue, water, rice);
        assertThat(AssistantService.matchedItems(stock, "卫生纸")).containsExactly(tissue);
        assertThat(AssistantService.matchedItems(stock, "水")).containsExactly(water);
        assertThat(AssistantService.matchedItems(stock, "米")).containsExactly(rice);
        assertThat(AssistantService.matchedItems(stock, "大米")).containsExactly(rice);
        assertThat(AssistantService.matchedItems(stock, "助行器")).isEmpty();
    }
}
