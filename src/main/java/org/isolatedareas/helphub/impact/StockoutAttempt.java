package org.isolatedareas.helphub.impact;

public record StockoutAttempt(long residentId, long requestId, long inventoryItemId,
                              int requestedQuantity, int availableQuantity) {}
