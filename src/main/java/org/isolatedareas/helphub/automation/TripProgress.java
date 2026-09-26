package org.isolatedareas.helphub.automation;

import java.time.Instant;
import java.util.List;

/** Where a simulated cart is at a given moment, interpolated along its planned straight-line legs. */
public final class TripProgress {
    private TripProgress() {
    }

    public static Position at(double startLat, double startLon, Instant startedAt, List<TimedStop> stops, Instant now) {
        double lat = startLat;
        double lon = startLon;
        Instant departed = startedAt;
        for (int index = 0; index < stops.size(); index++) {
            TimedStop stop = stops.get(index);
            if (now.isBefore(stop.arriveAt())) {
                long legMillis = Math.max(1, stop.arriveAt().toEpochMilli() - departed.toEpochMilli());
                double fraction = Math.min(1, Math.max(0, (now.toEpochMilli() - departed.toEpochMilli()) / (double) legMillis));
                return new Position(lat + (stop.latitude() - lat) * fraction, lon + (stop.longitude() - lon) * fraction,
                    index, false);
            }
            if (now.isBefore(stop.departAt())) {
                return new Position(stop.latitude(), stop.longitude(), index, true);
            }
            lat = stop.latitude();
            lon = stop.longitude();
            departed = stop.departAt();
        }
        return new Position(lat, lon, stops.size(), true);
    }

    public record TimedStop(double latitude, double longitude, Instant arriveAt, Instant departAt) {
    }

    /** nextStopIndex == stops.size() means the trip is over; atStop means the cart is parked there. */
    public record Position(double latitude, double longitude, int nextStopIndex, boolean atStop) {
    }
}
