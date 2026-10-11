package org.isolatedareas.helphub.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validation;
import java.time.Instant;
import java.util.List;
import org.isolatedareas.helphub.inventory.InventoryItemView;
import org.isolatedareas.helphub.inventory.InventoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AssistantToolExecutorCategoryTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private InventoryRepository inventory;
    private ConfirmationService confirmations;
    private AssistantToolExecutor executor;

    @BeforeEach
    void setUp() {
        inventory = mock(InventoryRepository.class);
        confirmations = mock(ConfirmationService.class);
        var requestService = mock(org.isolatedareas.helphub.requests.RequestService.class);
        when(requestService.nearestServicePointMeters(org.mockito.ArgumentMatchers.anyDouble(),
            org.mockito.ArgumentMatchers.anyDouble())).thenReturn(1_000.0);
        when(confirmations.create(anyLong(), anyString(), any(), anyString()))
            .thenAnswer(call -> new AssistantModels.Confirmation("token", call.getArgument(3), 300));
        when(inventory.list()).thenReturn(List.of(item(1, "FOOD"), item(2, "WATER"), item(3, "MEDICAL"),
            named(11, "公益大米 5 千克", "FOOD", 0), named(12, "公益大米 5 千克", "FOOD", 40),
            named(13, "成人纸尿裤 L 码", "HYGIENE", 9), named(14, "婴儿纸尿裤 M 码", "HYGIENE", 9)));
        executor = new AssistantToolExecutor(inventory, null, null, null, confirmations,
            Validation.buildDefaultValidatorFactory().getValidator(), null, requestService, null, null, null);
    }

    @Test
    void narrowsToASingleCategoryWhenTheModelPassesAKnownOne() {
        assertThat(categories("{\"category\":\"WATER\"}")).containsExactly("WATER");
        assertThat(categories("{\"category\":\"water\"}")).containsExactly("WATER");
    }

    @Test
    void returnsEverythingWhenTheModelPassesACatchAllOrNothing() {
        // Providers routinely emit "all" (or omit the field) to mean "no filter"; treating
        // that as a literal category silently returned an empty inventory.
        assertThat(categories("{\"category\":\"all\"}")).containsExactlyInAnyOrder("FOOD", "WATER", "MEDICAL", "HYGIENE");
        assertThat(categories("{\"category\":\"\"}")).containsExactlyInAnyOrder("FOOD", "WATER", "MEDICAL", "HYGIENE");
        assertThat(categories("{}")).containsExactlyInAnyOrder("FOOD", "WATER", "MEDICAL", "HYGIENE");
    }

    @Test
    void findsTheStockedItemFromWhatTheResidentAskedFor() throws Exception {
        // The model made up item id 999 and no category: the item is found by its name instead,
        // at a point that still has some free.
        JsonNode stored = prepare("{\"itemDescription\":\"大米\",\"quantity\":2,\"inventoryItemId\":999}");
        assertThat(stored.path("inventoryItemId").asLong()).isEqualTo(12);
        assertThat(stored.path("category").asText()).isEqualTo("FOOD");
        assertThat(stored.path("itemDescription").asText()).isEqualTo("公益大米 5 千克");
    }

    @Test
    void sendsAnythingNotStockedToStaffAsOther() throws Exception {
        JsonNode stored = prepare("{\"itemDescription\":\"老人用的助行器\",\"quantity\":1}");
        assertThat(stored.has("inventoryItemId")).isFalse();
        assertThat(stored.path("category").asText()).isEqualTo("OTHER");
    }

    @Test
    void tellsPacksOfOneProductApartByTheNumbersTheResidentSays() {
        var water = List.of("公益饮用水 12 瓶装", "公益饮用水 550 毫升×24 瓶");
        assertThat(AssistantToolExecutor.narrowed(water, "饮用水")).isEqualTo(water);
        assertThat(AssistantToolExecutor.narrowed(water, "24瓶的饮用水")).containsExactly("公益饮用水 550 毫升×24 瓶");
        assertThat(AssistantToolExecutor.narrowed(water, "公益饮用水 12 瓶装")).containsExactly("公益饮用水 12 瓶装");
        assertThat(AssistantToolExecutor.narrowed(water, "饮用水 99 瓶")).isEqualTo(water);
        // The model passed only "饮用水"; the resident's answer to "which one?" decides.
        assertThat(AssistantToolExecutor.narrowed(water, "饮用水", "the 24 bottle one")).containsExactly("公益饮用水 550 毫升×24 瓶");
        assertThat(AssistantToolExecutor.narrowed(water, "饮用水", "第一种")).containsExactly("公益饮用水 12 瓶装");
        assertThat(AssistantToolExecutor.narrowed(water, "饮用水", "the second one")).containsExactly("公益饮用水 550 毫升×24 瓶");
        assertThat(AssistantToolExecutor.narrowed(water, "饮用水", "2")).containsExactly("公益饮用水 550 毫升×24 瓶");
        assertThat(AssistantToolExecutor.narrowed(water, "饮用水", "I need some drinking water, one case")).isEqualTo(water);
        // A quantity is not a pack size.
        assertThat(AssistantToolExecutor.narrowed(water, "饮用水", "要1箱水")).isEqualTo(water);
        assertThat(AssistantToolExecutor.narrowed(water, "饮用水", "要2箱水")).isEqualTo(water);
        assertThat(AssistantToolExecutor.narrowed(water, "饮用水", "要两箱24瓶的")).containsExactly("公益饮用水 550 毫升×24 瓶");
        // The question lists the choices for the resident to pick from.
        assertThat(ResidentQuestion.whichItem("饮用水", water).getMessage())
            .isEqualTo("库存里有几种“饮用水”：\n1. 公益饮用水 12 瓶装\n2. 公益饮用水 550 毫升×24 瓶\n请告诉我要哪一种。");
    }

    @Test
    void asksWhichOneWhenSeveralProductsMatch() {
        assertThatThrownBy(() -> prepare("{\"itemDescription\":\"纸尿裤\",\"quantity\":1}"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("成人纸尿裤 L 码").hasMessageContaining("婴儿纸尿裤 M 码");
    }

    @Test
    void asksForWhatTheFormRequiresInsteadOfGuessing() throws Exception {
        var noMethod = (com.fasterxml.jackson.databind.node.ObjectNode) JSON.readTree("{\"itemDescription\":\"大米\",\"quantity\":2}");
        noMethod.put("latitude", 31.15).put("longitude", 121.12);
        assertThatThrownBy(() -> executor.execute(1L, "prepare_supply_request", noMethod)).hasMessageContaining("自取");
        assertThatThrownBy(() -> prepare("{\"itemDescription\":\"大米\"}")).hasMessageContaining("多少");
        assertThatThrownBy(() -> prepare("{\"itemDescription\":\"东西\",\"quantity\":1}")).hasMessageContaining("什么物资");
    }

    private JsonNode prepare(String arguments) throws Exception {
        var args = (com.fasterxml.jackson.databind.node.ObjectNode) JSON.readTree(arguments);
        args.put("latitude", 31.15).put("longitude", 121.12);
        if (!args.has("fulfillmentMethod")) args.put("fulfillmentMethod", "PICKUP");
        var result = executor.execute(1L, "prepare_supply_request", args);
        assertThat(result.confirmation()).isNotNull();
        ArgumentCaptor<JsonNode> stored = ArgumentCaptor.forClass(JsonNode.class);
        verify(confirmations).create(eq(1L), eq("prepare_supply_request"), stored.capture(), anyString());
        return stored.getValue();
    }

    private List<String> categories(String arguments) {
        try {
            Object result = executor.execute(1L, "check_inventory", JSON.readTree(arguments)).result();
            @SuppressWarnings("unchecked")
            List<InventoryItemView> items = (List<InventoryItemView>) result;
            return items.stream().map(InventoryItemView::category).distinct().toList();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static InventoryItemView item(long id, String category) {
        return new InventoryItemView(id, 1, "站点", "地址", "SKU-" + id, "物资 " + id, category,
            "件", 0, 10, 0, 10, 5, 0, Instant.EPOCH);
    }

    private static InventoryItemView named(long id, String name, String category, int free) {
        return new InventoryItemView(id, 1, "站点", "地址", "SKU-" + id, name, category,
            "袋", 0, free, 0, free, 5, 0, Instant.EPOCH);
    }
}
