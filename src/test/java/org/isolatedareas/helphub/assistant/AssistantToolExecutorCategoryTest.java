package org.isolatedareas.helphub.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import org.isolatedareas.helphub.inventory.InventoryItemView;
import org.isolatedareas.helphub.inventory.InventoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AssistantToolExecutorCategoryTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private InventoryRepository inventory;
    private AssistantToolExecutor executor;

    @BeforeEach
    void setUp() {
        inventory = mock(InventoryRepository.class);
        when(inventory.list()).thenReturn(List.of(item(1, "FOOD"), item(2, "WATER"), item(3, "MEDICAL")));
        executor = new AssistantToolExecutor(inventory, null, null, null, null, null, null, null);
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
        assertThat(categories("{\"category\":\"all\"}")).hasSize(3);
        assertThat(categories("{\"category\":\"\"}")).hasSize(3);
        assertThat(categories("{}")).hasSize(3);
    }

    private List<String> categories(String arguments) {
        try {
            Object result = executor.execute(1L, "check_inventory", JSON.readTree(arguments)).result();
            @SuppressWarnings("unchecked")
            List<InventoryItemView> items = (List<InventoryItemView>) result;
            return items.stream().map(InventoryItemView::category).toList();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static InventoryItemView item(long id, String category) {
        return new InventoryItemView(id, 1, "站点", "地址", "SKU-" + id, "物资 " + id, category,
            "件", 0, 10, 0, 10, 5, 0, Instant.EPOCH);
    }
}
