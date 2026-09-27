package org.isolatedareas.helphub.geo;

import java.security.SecureRandom;
import java.util.List;
import java.util.random.RandomGenerator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Where the programme serves: within {@link #RADIUS_METERS} of an active service point, which a
 * cart reaches on a short trip. Every resident starts with a random point in this area as their
 * default location, so a request sent without picking one is still deliverable.
 */
@Component
public class ServiceArea {
    public static final double RADIUS_METERS = 3000;
    private static final double METERS_PER_DEGREE_LATITUDE = 111_320;
    /** Qingpu, used only if no service point is active. */
    private static final double[] FALLBACK_CENTRE = {31.1665, 121.1111};

    private final JdbcClient jdbc;
    private final RandomGenerator random;

    @Autowired
    public ServiceArea(JdbcClient jdbc) {
        this(jdbc, new SecureRandom());
    }

    ServiceArea(JdbcClient jdbc, RandomGenerator random) {
        this.jdbc = jdbc;
        this.random = random;
    }

    /** A uniformly random point within the radius of a randomly chosen active service point. */
    public double[] randomPoint() {
        List<double[]> centres = jdbc.sql("SELECT latitude, longitude FROM service_points WHERE status='ACTIVE' ORDER BY id")
            .query((rs, n) -> new double[] {rs.getDouble("latitude"), rs.getDouble("longitude")}).list();
        double[] centre = centres.isEmpty() ? FALLBACK_CENTRE : centres.get(random.nextInt(centres.size()));
        // sqrt keeps the density even across the disc instead of crowding the centre.
        double distance = RADIUS_METERS * Math.sqrt(random.nextDouble());
        double bearing = 2 * Math.PI * random.nextDouble();
        return offset(centre[0], centre[1], distance, bearing);
    }

    static double[] offset(double latitude, double longitude, double distanceMeters, double bearing) {
        double dLat = distanceMeters * Math.cos(bearing) / METERS_PER_DEGREE_LATITUDE;
        double dLon = distanceMeters * Math.sin(bearing) / (METERS_PER_DEGREE_LATITUDE * Math.cos(Math.toRadians(latitude)));
        return new double[] {round(latitude + dLat), round(longitude + dLon)};
    }

    private static double round(double degrees) {
        return Math.round(degrees * 1_000_000) / 1_000_000.0;
    }
}
