package org.isolatedareas.helphub.geo;

public final class GeoMath {
    private static final double EARTH_RADIUS_METERS = 6_371_008.8;

    private GeoMath() {
    }

    public static double distanceMeters(double lat1, double lon1, double lat2, double lon2) {
        double phi1 = Math.toRadians(lat1);
        double phi2 = Math.toRadians(lat2);
        double deltaPhi = Math.toRadians(lat2 - lat1);
        double deltaLambda = Math.toRadians(lon2 - lon1);
        double a = Math.sin(deltaPhi / 2) * Math.sin(deltaPhi / 2)
            + Math.cos(phi1) * Math.cos(phi2)
            * Math.sin(deltaLambda / 2) * Math.sin(deltaLambda / 2);
        return EARTH_RADIUS_METERS * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }
}

