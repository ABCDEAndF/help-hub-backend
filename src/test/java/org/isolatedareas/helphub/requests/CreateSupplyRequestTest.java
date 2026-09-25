package org.isolatedareas.helphub.requests;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.math.BigDecimal;
import java.time.Instant;
import org.isolatedareas.helphub.domain.Urgency;
import org.junit.jupiter.api.Test;

class CreateSupplyRequestTest {
    @Test
    void rejectsAnEndBeforeThePreferredStart() {
        Instant start = Instant.parse("2026-09-06T10:00:00Z");
        assertThatThrownBy(() -> new CreateSupplyRequest("FOOD", "大米", 1, Urgency.NORMAL,
            new BigDecimal("31.23"), new BigDecimal("121.47"), null, null,
            start, start.minusSeconds(60)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("preferredEnd");
    }
}
