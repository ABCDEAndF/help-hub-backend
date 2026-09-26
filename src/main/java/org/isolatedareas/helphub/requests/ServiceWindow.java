package org.isolatedareas.helphub.requests;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * The time a resident books for delivery or pickup: either "as soon as possible" (no window)
 * or a daytime slot on one of the next seven days, in Shanghai time.
 */
public final class ServiceWindow {
    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    static final LocalTime DAY_START = LocalTime.of(9, 0);
    static final LocalTime DAY_END = LocalTime.of(17, 0);
    static final int MAX_DAYS_AHEAD = 7;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("M月d日");
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm");

    private ServiceWindow() {
    }

    /** Rejects windows that are half-specified, already over, too far ahead or outside service hours. */
    public static void validate(Instant start, Instant end, Instant now) {
        if (start == null && end == null) return;
        if (start == null || end == null || !end.isAfter(start)) throw invalid();
        ZonedDateTime from = start.atZone(ZONE);
        ZonedDateTime to = end.atZone(ZONE);
        boolean sameDay = from.toLocalDate().equals(to.toLocalDate());
        boolean withinHours = !from.toLocalTime().isBefore(DAY_START) && !to.toLocalTime().isAfter(DAY_END);
        boolean notOver = end.isAfter(now);
        boolean notTooFar = !from.toLocalDate().isAfter(now.atZone(ZONE).toLocalDate().plusDays(MAX_DAYS_AHEAD));
        if (!sameDay || !withinHours || !notOver || !notTooFar) throw invalid();
    }

    /** The part of the window a service point is open, or null when it is closed throughout. */
    public static Instant[] overlapWithHours(Instant start, Instant end, LocalTime opens, LocalTime closes) {
        if (opens == null || closes == null) return new Instant[] {start, end};
        ZonedDateTime day = start.atZone(ZONE);
        Instant open = day.with(opens).toInstant();
        Instant close = day.with(closes).toInstant();
        Instant from = start.isAfter(open) ? start : open;
        Instant to = end.isBefore(close) ? end : close;
        return to.isAfter(from) ? new Instant[] {from, to} : null;
    }

    /** "9月28日 13:00–16:30". */
    public static String describe(Instant start, Instant end) {
        ZonedDateTime from = start.atZone(ZONE);
        return DATE.format(from) + " " + CLOCK.format(from) + "–" + CLOCK.format(end.atZone(ZONE));
    }

    /** How long a booked pickup code stays valid: 48 hours, counted from the end of a booked window. */
    public static Instant holdUntil(Instant now, Instant windowEnd, Duration hold) {
        Instant fromNow = now.plus(hold);
        if (windowEnd == null) return fromNow;
        Instant fromWindow = windowEnd.plus(hold);
        return fromWindow.isAfter(fromNow) ? fromWindow : fromNow;
    }

    private static ResponseStatusException invalid() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid service window");
    }
}
