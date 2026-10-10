package org.isolatedareas.helphub.simulation;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.SplittableRandom;

/**
 * What one resident does on one day: when they order and what kind of order, when they would have
 * talked to the assistant (planned but no longer carried out, see ResidentActivitySimulator), and
 * when they check whether a pickup is due or a finished request deserves
 * feedback. The plan depends only on the user, their first sign-in and the date, so it is the
 * same on every instance; anything that depends on live data (stock, their requests) is decided
 * when the activity is carried out.
 */
public final class DailyActivityPlanner {
    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    public enum Type { ORDER, CHAT, PICKUP_CHECK, FEEDBACK_CHECK }

    public enum ChatTopic { INVENTORY, SERVICE_POINTS, PROGRESS, RESERVATIONS, HOW_TO }

    /** A booked slot: days from today and morning (true) or afternoon. */
    public record Booking(int daysAhead, boolean morning) {
    }

    /** Everything about an order that does not depend on live stock. */
    public record OrderIntent(boolean manual, int quantityWish, String urgency, boolean delivery,
                              Booking booking, int noteIndex, double awayBearing, double awayMeters,
                              long choiceSeed) {
    }

    /** One message in a conversation; seq 0 opens a new conversation. */
    public record ChatLine(ChatTopic topic, int variant, int seq) {
    }

    public record Activity(Instant at, long userId, int index, Type type, OrderIntent order, ChatLine chat) {
    }

    private DailyActivityPlanner() {
    }

    public static List<Activity> plan(long userId, Instant activatedAt, LocalDate day) {
        LocalDate firstDay = activatedAt.atZone(ZONE).toLocalDate();
        int dayIndex = (int) ChronoUnit.DAYS.between(firstDay, day);
        if (dayIndex < 0 || dayIndex >= ResidentPersona.SIMULATED_DAYS) return List.of();
        ResidentPersona persona = ResidentPersona.of(userId);
        SplittableRandom random = new SplittableRandom(ResidentPersona.mix(userId, day.toEpochDay()));
        List<Draft> drafts = new ArrayList<>();

        boolean weekend = day.getDayOfWeek().getValue() >= 6;
        boolean engaged = dayIndex < persona.activeDays() && !persona.onBreak(dayIndex);
        // Former short-term residents occasionally come back for a single order.
        double orderChance = engaged ? persona.dailyOrderChance() : persona.kind() == ResidentPersona.Kind.SHORT_TERM ? 0.01 : 0;
        if (weekend) orderChance *= persona.weekendFactor();
        // Someone who just signed in is usually there because they need something.
        if (dayIndex == 0) orderChance = Math.max(orderChance, 0.55);
        int orders = random.nextDouble() < Math.min(orderChance, 0.97) ? 1 + (random.nextDouble() < persona.secondOrderChance() ? 1 : 0) : 0;

        for (int n = 0; n < orders; n++) {
            LocalTime time = orderTime(random, persona.peakHour());
            OrderIntent intent = orderIntent(random, persona);
            drafts.add(new Draft(time, Type.ORDER, intent, null));
            if (random.nextDouble() < persona.progressCheckChance()) {
                LocalTime check = time.plusMinutes(20 + random.nextInt(220));
                if (check.isAfter(time)) drafts.add(new Draft(check, Type.CHAT, null,
                    new ChatLine(random.nextDouble() < 0.7 ? ChatTopic.PROGRESS : ChatTopic.RESERVATIONS, random.nextInt(8), 0)));
            }
        }

        double chatChance = engaged ? persona.chatChance() : persona.chatChance() / 6;
        if (dayIndex == 0) chatChance = Math.max(chatChance, 0.5);
        if (random.nextDouble() < chatChance) {
            LocalTime start = orderTime(random, persona.peakHour());
            int lines = 1 + (random.nextDouble() < 0.45 ? 1 : 0) + (random.nextDouble() < 0.2 ? 1 : 0);
            LocalTime at = start;
            for (int seq = 0; seq < lines; seq++) {
                ChatTopic topic = seq == 0 && dayIndex == 0 && random.nextDouble() < 0.5
                    ? ChatTopic.HOW_TO : ChatTopic.values()[random.nextInt(ChatTopic.values().length)];
                drafts.add(new Draft(at, Type.CHAT, null, new ChatLine(topic, random.nextInt(8), seq)));
                LocalTime next = at.plusSeconds(40 + random.nextInt(260));
                if (next.isBefore(at)) break;
                at = next;
            }
        }

        // Pickups are collected during opening hours; the check does nothing when nothing is due.
        if (persona.deliveryShare() < 0.999) {
            drafts.add(new Draft(LocalTime.of(9, 20).plusMinutes(random.nextInt(160)), Type.PICKUP_CHECK, null, null));
            drafts.add(new Draft(LocalTime.of(13, 20).plusMinutes(random.nextInt(170)), Type.PICKUP_CHECK, null, null));
        }
        drafts.add(new Draft(LocalTime.of(18, 30).plusMinutes(random.nextInt(210)), Type.FEEDBACK_CHECK, null, null));

        List<Activity> activities = new ArrayList<>();
        drafts.sort(Comparator.comparing(Draft::time));
        int index = 0;
        for (Draft draft : drafts) {
            // A few milliseconds per user keep two residents' activities from sharing an instant.
            Instant at = day.atTime(draft.time()).atZone(ZONE).toInstant().plusMillis(Math.floorMod(userId, 997));
            if (!at.isAfter(activatedAt)) continue;
            activities.add(new Activity(at, userId, index++, draft.type(), draft.order(), draft.chat()));
        }
        return activities;
    }

    /** Mostly around the resident's usual hour, always between 07:00 and 22:30. */
    static LocalTime orderTime(SplittableRandom random, int peakHour) {
        double hour = random.nextDouble() < 0.8
            ? peakHour + gaussian(random) * 2.2
            : 7 + random.nextDouble() * 15.5;
        hour = Math.max(7, Math.min(22.5, hour));
        int seconds = (int) Math.round(hour * 3600) + random.nextInt(60);
        return LocalTime.ofSecondOfDay(Math.min(seconds, 22 * 3600 + 30 * 60));
    }

    private static OrderIntent orderIntent(SplittableRandom random, ResidentPersona persona) {
        double urgencyRoll = random.nextDouble();
        String urgency = urgencyRoll < 0.10 ? "LOW" : urgencyRoll < 0.85 ? "NORMAL" : urgencyRoll < 0.97 ? "HIGH" : "CRITICAL";
        boolean delivery = random.nextDouble() < persona.deliveryShare();
        Booking booking = random.nextDouble() < persona.bookSlotChance()
            ? new Booking(random.nextInt(3), random.nextBoolean()) : null;
        boolean away = random.nextDouble() < 0.1;
        return new OrderIntent(random.nextDouble() < persona.manualRequestChance(),
            1 + random.nextInt(persona.maxQuantity()), urgency, delivery, booking,
            random.nextDouble() < 0.18 ? random.nextInt(Pools.NOTES.size()) : -1,
            random.nextDouble() * 2 * Math.PI, away ? 150 + random.nextDouble() * 600 : 0,
            random.nextLong());
    }

    private static double gaussian(SplittableRandom random) {
        double u = Math.max(random.nextDouble(), 1e-12);
        return Math.sqrt(-2 * Math.log(u)) * Math.cos(2 * Math.PI * random.nextDouble());
    }

    private record Draft(LocalTime time, Type type, OrderIntent order, ChatLine chat) {
    }
}
