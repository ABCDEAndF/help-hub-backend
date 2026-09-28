package org.isolatedareas.helphub.simulation;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DailyActivityPlannerTest {
    private static final Instant ACTIVATED = LocalDate.of(2026, 9, 28).atTime(10, 15)
        .atZone(DailyActivityPlanner.ZONE).toInstant();

    private static int ordersOverAllDays(long userId) {
        int orders = 0;
        LocalDate first = ACTIVATED.atZone(DailyActivityPlanner.ZONE).toLocalDate();
        for (int day = 0; day < ResidentPersona.SIMULATED_DAYS; day++) {
            orders += (int) DailyActivityPlanner.plan(userId, ACTIVATED, first.plusDays(day)).stream()
                .filter(activity -> activity.type() == DailyActivityPlanner.Type.ORDER).count();
        }
        return orders;
    }

    @Test
    void residentsRangeFromNearlyDailyToShortLived() {
        Map<ResidentPersona.Kind, int[]> totals = new EnumMap<>(ResidentPersona.Kind.class);
        int devotedOverSixty = 0;
        int devoted = 0;
        for (long userId = 1; userId <= 400; userId++) {
            ResidentPersona.Kind kind = ResidentPersona.of(userId).kind();
            int orders = ordersOverAllDays(userId);
            int[] sum = totals.computeIfAbsent(kind, k -> new int[2]);
            sum[0] += orders;
            sum[1]++;
            if (kind == ResidentPersona.Kind.DEVOTED) {
                devoted++;
                if (orders > 60) devotedOverSixty++;
            }
        }
        assertThat(totals.keySet()).containsExactlyInAnyOrder(ResidentPersona.Kind.values());
        assertThat(devoted).isGreaterThan(30);
        // Most devoted residents order more than 60 times in their 90 days.
        assertThat(devotedOverSixty).isGreaterThan(devoted * 3 / 4);
        double devotedMean = (double) totals.get(ResidentPersona.Kind.DEVOTED)[0] / totals.get(ResidentPersona.Kind.DEVOTED)[1];
        double occasionalMean = (double) totals.get(ResidentPersona.Kind.OCCASIONAL)[0] / totals.get(ResidentPersona.Kind.OCCASIONAL)[1];
        assertThat(devotedMean).isGreaterThan(60);
        assertThat(occasionalMean).isBetween(1.0, 15.0);
    }

    @Test
    void shortTermResidentsStopOrderingAfterTheirWeeks() {
        long userId = 1;
        while (ResidentPersona.of(userId).kind() != ResidentPersona.Kind.SHORT_TERM) userId++;
        ResidentPersona persona = ResidentPersona.of(userId);
        LocalDate first = ACTIVATED.atZone(DailyActivityPlanner.ZONE).toLocalDate();
        int late = 0;
        for (int day = persona.activeDays(); day < ResidentPersona.SIMULATED_DAYS; day++) {
            late += (int) DailyActivityPlanner.plan(userId, ACTIVATED, first.plusDays(day)).stream()
                .filter(activity -> activity.type() == DailyActivityPlanner.Type.ORDER).count();
        }
        assertThat(late).isLessThanOrEqualTo(5);
    }

    @Test
    void planIsTheSameEveryTimeAndOrderedWithinTheDay() {
        LocalDate day = LocalDate.of(2026, 10, 3);
        for (long userId = 1; userId <= 50; userId++) {
            List<DailyActivityPlanner.Activity> plan = DailyActivityPlanner.plan(userId, ACTIVATED, day);
            assertThat(DailyActivityPlanner.plan(userId, ACTIVATED, day)).isEqualTo(plan);
            Instant previous = Instant.MIN;
            for (DailyActivityPlanner.Activity activity : plan) {
                assertThat(activity.at()).isAfterOrEqualTo(previous);
                previous = activity.at();
                LocalTime clock = activity.at().atZone(DailyActivityPlanner.ZONE).toLocalTime();
                assertThat(clock).isBetween(LocalTime.of(7, 0), LocalTime.of(23, 59));
                assertThat(activity.at().atZone(DailyActivityPlanner.ZONE).toLocalDate()).isEqualTo(day);
            }
        }
    }

    @Test
    void nothingBeforeFirstSignInOrAfterNinetyDays() {
        LocalDate first = ACTIVATED.atZone(DailyActivityPlanner.ZONE).toLocalDate();
        for (long userId = 1; userId <= 100; userId++) {
            assertThat(DailyActivityPlanner.plan(userId, ACTIVATED, first.minusDays(1))).isEmpty();
            assertThat(DailyActivityPlanner.plan(userId, ACTIVATED, first.plusDays(ResidentPersona.SIMULATED_DAYS))).isEmpty();
            assertThat(DailyActivityPlanner.plan(userId, ACTIVATED, first))
                .allSatisfy(activity -> assertThat(activity.at()).isAfter(ACTIVATED));
        }
    }

    @Test
    void bookedSlotFallsBackToAsSoonAsPossibleOnceOver() {
        Instant afternoon = LocalDate.of(2026, 10, 3).atTime(15, 0).atZone(DailyActivityPlanner.ZONE).toInstant();
        Instant[] morningToday = ResidentActivitySimulator.window(new DailyActivityPlanner.Booking(0, true), afternoon);
        assertThat(morningToday).containsOnlyNulls();
        Instant[] tomorrow = ResidentActivitySimulator.window(new DailyActivityPlanner.Booking(1, false), afternoon);
        assertThat(tomorrow[0].atZone(DailyActivityPlanner.ZONE).toLocalTime()).isEqualTo(LocalTime.of(13, 0));
        assertThat(tomorrow[1].atZone(DailyActivityPlanner.ZONE).toLocalTime()).isEqualTo(LocalTime.of(17, 0));
    }
}
