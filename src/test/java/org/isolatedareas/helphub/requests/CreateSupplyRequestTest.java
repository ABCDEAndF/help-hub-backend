package org.isolatedareas.helphub.requests;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import org.isolatedareas.helphub.domain.FulfillmentMethod;
import org.isolatedareas.helphub.domain.Urgency;
import org.junit.jupiter.api.Test;

class CreateSupplyRequestTest {
    @Test
    void rejectsAnEndBeforeThePreferredStart() {
        Instant start = Instant.parse("2026-09-06T10:00:00Z");
        assertThatThrownBy(() -> new CreateSupplyRequest("FOOD", "大米", 1, Urgency.NORMAL,
            new BigDecimal("31.23"), new BigDecimal("121.47"), null, null,
            start, start.minusSeconds(60), FulfillmentMethod.PICKUP))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("preferredEnd");
    }

    @Test
    void olderClientsWithoutAChoiceKeepCartDelivery() throws Exception {
        var input = new ObjectMapper().findAndRegisterModules().readValue("""
            {"category":"FOOD","itemDescription":"大米","quantity":1,"urgency":"NORMAL",
             "latitude":31.15,"longitude":121.12}
            """, CreateSupplyRequest.class);

        assertThat(input.fulfillmentMethod()).isEqualTo(FulfillmentMethod.DELIVERY);
    }

    @Test
    void acceptsPickupChosenByTheResident() throws Exception {
        var input = new ObjectMapper().findAndRegisterModules().readValue("""
            {"category":"FOOD","itemDescription":"大米","quantity":1,"urgency":"NORMAL",
             "latitude":31.15,"longitude":121.12,"fulfillmentMethod":"PICKUP"}
            """, CreateSupplyRequest.class);

        assertThat(input.fulfillmentMethod()).isEqualTo(FulfillmentMethod.PICKUP);
    }
}
