package org.isolatedareas.helphub.simulation;

import java.util.SplittableRandom;

/**
 * How one resident behaves over their 90 days: how often they order, for how long, what time of
 * day, delivery or pickup, how chatty and how likely to leave feedback. Derived from the user id
 * alone, so every instance and every restart sees the same person.
 */
public record ResidentPersona(
    Kind kind,
    int activeDays,
    double dailyOrderChance,
    double secondOrderChance,
    double weekendFactor,
    double deliveryShare,
    double bookSlotChance,
    double chatChance,
    double progressCheckChance,
    double feedbackChance,
    double noShowChance,
    double manualRequestChance,
    int peakHour,
    int maxQuantity,
    int breakStartDay,
    int breakLength,
    long favouriteSeed
) {
    /** DEVOTED residents order almost every day (60+ orders in 90 days); SHORT_TERM ones stop after a few weeks. */
    public enum Kind { DEVOTED, REGULAR, OCCASIONAL, SHORT_TERM }

    public static final int SIMULATED_DAYS = 90;
    private static final int[] PEAK_HOURS = {8, 9, 10, 12, 15, 17, 19, 20};

    public static ResidentPersona of(long userId) {
        SplittableRandom random = new SplittableRandom(mix(userId, 0x5eed_7e51_dL));
        double roll = random.nextDouble();
        Kind kind = roll < 0.15 ? Kind.DEVOTED : roll < 0.45 ? Kind.REGULAR : roll < 0.75 ? Kind.OCCASIONAL : Kind.SHORT_TERM;
        int activeDays = kind == Kind.SHORT_TERM ? between(random, 4, 25) : SIMULATED_DAYS;
        double orderChance = switch (kind) {
            case DEVOTED -> uniform(random, 0.62, 0.85);
            case REGULAR -> uniform(random, 0.15, 0.35);
            case OCCASIONAL -> uniform(random, 0.03, 0.09);
            case SHORT_TERM -> uniform(random, 0.35, 0.70);
        };
        double secondOrder = kind == Kind.DEVOTED ? uniform(random, 0.15, 0.30) : uniform(random, 0.02, 0.06);
        // Only long-term residents take a break (travel, illness); about half of them do.
        boolean takesBreak = kind != Kind.SHORT_TERM && random.nextDouble() < 0.5;
        return new ResidentPersona(kind, activeDays, orderChance, secondOrder,
            uniform(random, 0.6, 1.4), uniform(random, 0.2, 0.9), uniform(random, 0.1, 0.4),
            uniform(random, 0.03, 0.35), uniform(random, 0.1, 0.5), uniform(random, 0.15, 0.7),
            uniform(random, 0.03, 0.15), uniform(random, 0.02, 0.07),
            PEAK_HOURS[random.nextInt(PEAK_HOURS.length)], between(random, 1, 3),
            takesBreak ? between(random, 10, 80) : -1, takesBreak ? between(random, 2, 7) : 0,
            random.nextLong());
    }

    public boolean onBreak(int dayIndex) {
        return breakStartDay >= 0 && dayIndex >= breakStartDay && dayIndex < breakStartDay + breakLength;
    }

    static long mix(long a, long b) {
        long x = a * 0x9E3779B97F4A7C15L ^ b;
        x ^= x >>> 33;
        x *= 0xff51afd7ed558ccdL;
        x ^= x >>> 33;
        x *= 0xc4ceb9fe1a85ec53L;
        return x ^ (x >>> 33);
    }

    /** A fixed number in [0, 1) for a (salt, id) pair: the same decision every time it is asked. */
    static double unit(long salt, long id) {
        return (mix(salt, id) >>> 11) * 0x1.0p-53;
    }

    private static double uniform(SplittableRandom random, double from, double to) {
        return from + (to - from) * random.nextDouble();
    }

    private static int between(SplittableRandom random, int from, int toInclusive) {
        return from + random.nextInt(toInclusive - from + 1);
    }
}
