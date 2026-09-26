package org.isolatedareas.helphub.requests;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class ServiceWindowTest {
    // Saturday 26 September 2026, 10:30 in Shanghai.
    private static final Instant NOW = ZonedDateTime.of(2026, 9, 26, 10, 30, 0, 0, ServiceWindow.ZONE).toInstant();

    private static Instant at(int day, int hour, int minute) {
        return ZonedDateTime.of(2026, 9, day, hour, minute, 0, 0, ServiceWindow.ZONE).toInstant();
    }

    @Test
    void acceptsAsSoonAsPossibleAndDaytimeSlotsWithinSevenDays() {
        assertThatCode(() -> ServiceWindow.validate(null, null, NOW)).doesNotThrowAnyException();
        assertThatCode(() -> ServiceWindow.validate(at(26, 9, 0), at(26, 12, 0), NOW)).doesNotThrowAnyException();
        assertThatCode(() -> ServiceWindow.validate(at(27, 13, 0), at(27, 17, 0), NOW)).doesNotThrowAnyException();
        assertThatCode(() -> ServiceWindow.validate(
            ZonedDateTime.of(2026, 10, 3, 13, 0, 0, 0, ServiceWindow.ZONE).toInstant(),
            ZonedDateTime.of(2026, 10, 3, 17, 0, 0, 0, ServiceWindow.ZONE).toInstant(), NOW)).doesNotThrowAnyException();
    }

    @Test
    void rejectsPastFarAwayNightAndHalfSpecifiedWindows() {
        assertInvalid(at(25, 13, 0), at(25, 17, 0));
        assertInvalid(ZonedDateTime.of(2026, 10, 4, 9, 0, 0, 0, ServiceWindow.ZONE).toInstant(),
            ZonedDateTime.of(2026, 10, 4, 12, 0, 0, 0, ServiceWindow.ZONE).toInstant());
        assertInvalid(at(27, 18, 0), at(27, 20, 0));
        assertInvalid(at(27, 13, 0), null);
        assertInvalid(at(27, 12, 0), at(27, 9, 0));
    }

    @Test
    void pickupTimeIsTheSlotWithinTheServicePointsHours() {
        Instant[] xiayangAfternoon = ServiceWindow.overlapWithHours(at(27, 13, 0), at(27, 17, 0),
            LocalTime.of(8, 30), LocalTime.of(16, 30));
        assertThat(ServiceWindow.describe(xiayangAfternoon[0], xiayangAfternoon[1])).isEqualTo("9月27日 13:00–16:30");
        assertThat(ServiceWindow.overlapWithHours(at(27, 9, 0), at(27, 12, 0), LocalTime.of(13, 0), LocalTime.of(17, 0))).isNull();
    }

    @Test
    void aBookedPickupCodeLastsFortyEightHoursAfterTheSlot() {
        Duration hold = Duration.ofHours(48);
        assertThat(ServiceWindow.holdUntil(NOW, null, hold)).isEqualTo(NOW.plus(hold));
        Instant slotEnd = ZonedDateTime.of(2026, 10, 3, 17, 0, 0, 0, ServiceWindow.ZONE).toInstant();
        assertThat(ServiceWindow.holdUntil(NOW, slotEnd, hold)).isEqualTo(slotEnd.plus(hold));
    }

    private static void assertInvalid(Instant start, Instant end) {
        assertThatThrownBy(() -> ServiceWindow.validate(start, end, NOW)).isInstanceOf(ResponseStatusException.class);
    }
}
