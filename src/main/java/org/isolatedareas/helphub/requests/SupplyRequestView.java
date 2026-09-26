package org.isolatedareas.helphub.requests;

import java.math.BigDecimal;
import java.time.Instant;
import org.isolatedareas.helphub.domain.FulfillmentMethod;
import org.isolatedareas.helphub.domain.RequestStatus;
import org.isolatedareas.helphub.domain.Urgency;

public record SupplyRequestView(
    long id,
    long residentId,
    String residentName,
    String category,
    String itemDescription,
    int quantity,
    Urgency urgency,
    FulfillmentMethod fulfillmentMethod,
    RequestStatus status,
    BigDecimal latitude,
    BigDecimal longitude,
    String approximateAddress,
    String accessibilityNotes,
    Instant preferredStart,
    Instant preferredEnd,
    Long assignedServicePointId,
    Long assignedCartId,
    String assignedServicePointName,
    String assignedCartName,
    Instant createdAt,
    Instant updatedAt
) {
}

