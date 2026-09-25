package org.isolatedareas.helphub.routing;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.isolatedareas.helphub.geo.GeoMath;

public class RoutePlanningAlgorithm {
    public PlanningResult plan(List<CartInput> carts, List<StopInput> stops) {
        List<StopInput> prioritized = stops.stream()
            .sorted(Comparator.comparingInt((StopInput stop) -> urgencyRank(stop.urgency()))
                .thenComparing(Comparator.comparingInt(StopInput::demandUnits).reversed())
                .thenComparingLong(StopInput::requestId))
            .toList();
        Set<Long> assigned = new HashSet<>();
        List<CartRoute> routes = new ArrayList<>();
        double totalDistance = 0;
        double baselineDistance = 0;

        for (CartInput cart : carts) {
            int remaining = cart.capacityUnits();
            double currentLat = cart.latitude();
            double currentLon = cart.longitude();
            List<StopInput> selected = new ArrayList<>();

            while (true) {
                StopInput next = null;
                double nearest = Double.MAX_VALUE;
                int bestUrgency = Integer.MAX_VALUE;
                for (StopInput candidate : prioritized) {
                    if (assigned.contains(candidate.requestId()) || candidate.demandUnits() > remaining) continue;
                    int candidateUrgency = urgencyRank(candidate.urgency());
                    double distance = GeoMath.distanceMeters(currentLat, currentLon,
                        candidate.latitude(), candidate.longitude());
                    if (candidateUrgency < bestUrgency ||
                        (candidateUrgency == bestUrgency && distance < nearest)) {
                        bestUrgency = candidateUrgency;
                        nearest = distance;
                        next = candidate;
                    }
                }
                if (next == null) break;
                assigned.add(next.requestId());
                selected.add(next);
                remaining -= next.demandUnits();
                currentLat = next.latitude();
                currentLon = next.longitude();
            }

            List<StopInput> improved = twoOpt(cart.latitude(), cart.longitude(), selected);
            double optimized = routeDistance(cart.latitude(), cart.longitude(), improved);
            double baseline = independentTripDistance(cart.latitude(), cart.longitude(), selected);
            totalDistance += optimized;
            baselineDistance += baseline;
            List<PlannedStop> plannedStops = new ArrayList<>();
            int sequence = 1;
            for (StopInput stop : improved) {
                plannedStops.add(new PlannedStop(stop.requestId(), sequence++, stop.latitude(), stop.longitude(),
                    stop.demandUnits()));
            }
            routes.add(new CartRoute(cart.cartId(), cart.capacityUnits(), cart.capacityUnits() - remaining,
                Math.round(optimized), plannedStops));
        }

        List<Long> unassigned = prioritized.stream().map(StopInput::requestId)
            .filter(id -> !assigned.contains(id)).toList();
        return new PlanningResult(routes, unassigned, Math.round(totalDistance), Math.round(baselineDistance));
    }

    private List<StopInput> twoOpt(double depotLat, double depotLon, List<StopInput> input) {
        List<StopInput> route = new ArrayList<>(input);
        boolean improved = true;
        int passes = 0;
        while (improved && passes++ < 20) {
            improved = false;
            double bestDistance = routeDistance(depotLat, depotLon, route);
            for (int i = 0; i < route.size() - 1; i++) {
                for (int k = i + 1; k < route.size(); k++) {
                    List<StopInput> candidate = new ArrayList<>(route);
                    java.util.Collections.reverse(candidate.subList(i, k + 1));
                    double candidateDistance = routeDistance(depotLat, depotLon, candidate);
                    if (candidateDistance + 0.5 < bestDistance) {
                        route = candidate;
                        bestDistance = candidateDistance;
                        improved = true;
                    }
                }
            }
        }
        return route;
    }

    private double routeDistance(double depotLat, double depotLon, List<StopInput> route) {
        double distance = 0;
        double lat = depotLat;
        double lon = depotLon;
        for (StopInput stop : route) {
            distance += GeoMath.distanceMeters(lat, lon, stop.latitude(), stop.longitude());
            lat = stop.latitude();
            lon = stop.longitude();
        }
        if (!route.isEmpty()) distance += GeoMath.distanceMeters(lat, lon, depotLat, depotLon);
        return distance;
    }

    private double independentTripDistance(double depotLat, double depotLon, List<StopInput> route) {
        return route.stream().mapToDouble(stop -> 2 * GeoMath.distanceMeters(
            depotLat, depotLon, stop.latitude(), stop.longitude())).sum();
    }

    private int urgencyRank(String urgency) {
        return switch (urgency) {
            case "CRITICAL" -> 0;
            case "HIGH" -> 1;
            case "NORMAL" -> 2;
            default -> 3;
        };
    }

    public record CartInput(long cartId, int capacityUnits, double latitude, double longitude) {
    }
    public record StopInput(long requestId, int demandUnits, String urgency, double latitude, double longitude) {
    }
    public record PlannedStop(long requestId, int sequence, double latitude, double longitude, int demandUnits) {
    }
    public record CartRoute(long cartId, int capacityUnits, int assignedUnits, long distanceMeters,
                            List<PlannedStop> stops) {
    }
    public record PlanningResult(List<CartRoute> routes, List<Long> unassignedRequestIds,
                                 long totalDistanceMeters, long baselineDistanceMeters) {
    }
}
