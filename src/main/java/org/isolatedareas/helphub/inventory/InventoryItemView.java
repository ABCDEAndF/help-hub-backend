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
    Instant updatedAt,
    String initial
) {
    /** The pinyin initial is derived from the name (see {@link PinyinIndex}). */
    public InventoryItemView(long id, long servicePointId, String servicePointName, String servicePointAddress,
                             String sku, String name, String category, String unit, int unitPriceFen,
                             int availableQuantity, int reservedQuantity, int freeQuantity, int reorderThreshold,
                             long version, Instant updatedAt) {
        this(id, servicePointId, servicePointName, servicePointAddress, sku, name, category, unit, unitPriceFen,
            availableQuantity, reservedQuantity, freeQuantity, reorderThreshold, version, updatedAt,
            PinyinIndex.initial(name));
    }
}
