package org.isolatedareas.helphub.geo;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
public class DemandClusterService {
    private final JdbcClient jdbc;

    public DemandClusterService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<DemandCluster> clusters(double radiusMeters, int minimumPoints) {
        List<DemandPoint> points = jdbc.sql("""
                SELECT id, latitude, longitude, quantity, urgency
                FROM supply_requests
                WHERE status IN ('SUBMITTED','UNDER_REVIEW','APPROVED')
                ORDER BY created_at
                """)
            .query((rs, n) -> new DemandPoint(rs.getLong("id"), rs.getDouble("latitude"),
                rs.getDouble("longitude"), rs.getInt("quantity"), rs.getString("urgency")))
            .list();
        return dbscan(points, Math.max(100, Math.min(radiusMeters, 10_000)),
            Math.max(2, Math.min(minimumPoints, 50)));
    }

    public long fulfilledClusterCount(double radiusMeters, int minimumPoints) {
        List<DemandPoint> points = jdbc.sql("""
                SELECT id, latitude, longitude, quantity, urgency
                FROM supply_requests WHERE status='FULFILLED' ORDER BY fulfilled_at
                """)
            .query((rs, n) -> new DemandPoint(rs.getLong("id"), rs.getDouble("latitude"),
                rs.getDouble("longitude"), rs.getInt("quantity"), rs.getString("urgency"))).list();
        return dbscan(points, radiusMeters, minimumPoints).size();
    }

    List<DemandCluster> dbscan(List<DemandPoint> points, double radiusMeters, int minimumPoints) {
        Set<Long> visited = new HashSet<>();
        Set<Long> assigned = new HashSet<>();
        List<DemandCluster> result = new ArrayList<>();
        int clusterId = 1;
        for (DemandPoint point : points) {
            if (!visited.add(point.id())) continue;
            List<DemandPoint> neighbors = neighbors(point, points, radiusMeters);
            if (neighbors.size() < minimumPoints) continue;
            List<DemandPoint> members = new ArrayList<>();
            ArrayDeque<DemandPoint> queue = new ArrayDeque<>(neighbors);
            while (!queue.isEmpty()) {
                DemandPoint candidate = queue.removeFirst();
                if (visited.add(candidate.id())) {
                    List<DemandPoint> candidateNeighbors = neighbors(candidate, points, radiusMeters);
                    if (candidateNeighbors.size() >= minimumPoints) queue.addAll(candidateNeighbors);
                }
                if (assigned.add(candidate.id())) members.add(candidate);
            }
            result.add(toCluster(clusterId++, members));
        }
        return result;
    }

    private List<DemandPoint> neighbors(DemandPoint center, List<DemandPoint> points, double radiusMeters) {
        return points.stream()
            .filter(point -> GeoMath.distanceMeters(center.latitude(), center.longitude(),
                point.latitude(), point.longitude()) <= radiusMeters)
            .toList();
    }

    private DemandCluster toCluster(int id, List<DemandPoint> points) {
        double latitude = points.stream().mapToDouble(DemandPoint::latitude).average().orElse(0);
        double longitude = points.stream().mapToDouble(DemandPoint::longitude).average().orElse(0);
        int units = points.stream().mapToInt(DemandPoint::quantity).sum();
        Map<String, Long> urgency = points.stream()
            .collect(Collectors.groupingBy(DemandPoint::urgency, Collectors.counting()));
        return new DemandCluster(id, latitude, longitude, points.size(), units,
            urgency, points.stream().map(DemandPoint::id).toList());
    }

    record DemandPoint(long id, double latitude, double longitude, int quantity, String urgency) {
    }

    public record DemandCluster(int clusterId, double centroidLatitude, double centroidLongitude,
                                int requestCount, int totalUnits, Map<String, Long> urgencyCounts,
                                List<Long> requestIds) {
    }
}
