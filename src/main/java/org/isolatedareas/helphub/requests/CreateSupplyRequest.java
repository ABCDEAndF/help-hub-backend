package org.isolatedareas.helphub.requests;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import org.isolatedareas.helphub.domain.Urgency;

public record CreateSupplyRequest(
    @NotBlank @Size(max = 80) String category,
    @NotBlank @Size(max = 500) String itemDescription,
    @Min(1) @Max(100) int quantity,
    @NotNull Urgency urgency,
    @NotNull @DecimalMin("30.0") @DecimalMax("32.0") BigDecimal latitude,
    @NotNull @DecimalMin("120.0") @DecimalMax("123.0") BigDecimal longitude,
    @Size(max = 255) String approximateAddress,
    @Size(max = 500) String accessibilityNotes,
    Instant preferredStart,
    Instant preferredEnd
) {
    public CreateSupplyRequest {
        if (preferredStart != null && preferredEnd != null && !preferredEnd.isAfter(preferredStart)) {
            throw new IllegalArgumentException("preferredEnd must be after preferredStart");
        }
    }
}

