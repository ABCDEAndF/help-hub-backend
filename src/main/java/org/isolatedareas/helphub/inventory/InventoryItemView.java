package org.isolatedareas.helphub.inventory;

import java.time.Instant;

public record InventoryItemView(
    long id,
    long servicePointId,
    String servicePointName,
    String servicePointAddress,
    String sku,
    String name,
    String category,
    String unit,
    int unitPriceFen,
    int availableQuantity,
    int reservedQuantity,
    int freeQuantity,
    int reorderThreshold,
    long version,
    Instant updatedAt
) {
}
