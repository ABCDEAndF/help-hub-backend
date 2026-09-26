package org.isolatedareas.helphub.api;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

class IdempotencyKeysTest {
    @Test
    void prefersStandardHeaderWhenPresent() {
        assertThat(IdempotencyKeys.resolve("standard", "cloudrun")).isEqualTo("standard");
    }

    @Test
    void fallsBackToCloudRunSafeHeader() {
        assertThat(IdempotencyKeys.resolve(" ", "cloudrun")).isEqualTo("cloudrun");
    }

    @Test
    void fallsBackToGatewaySafeQueryParameter() {
        assertThat(IdempotencyKeys.resolve(null, " ", "query")).isEqualTo("query");
    }
}
