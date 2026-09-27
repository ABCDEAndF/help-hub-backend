package org.isolatedareas.helphub.automation;

import java.time.Instant;
import java.time.LocalTime;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.isolatedareas.helphub.domain.FulfillmentMethod;
import org.isolatedareas.helphub.domain.RequestStatus;
import org.isolatedareas.helphub.geo.GeoMath;
import org.isolatedareas.helphub.inventory.ReservationService;
import org.isolatedareas.helphub.requests.RequestService;
import org.isolatedareas.helphub.requests.SupplyRequestRepository;
import org.isolatedareas.helphub.requests.ServiceWindow;
import org.isolatedareas.helphub.requests.SupplyRequestView;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Approves a request for a stocked item when enough free stock exists, rejects it otherwise,
 * and reserves the stock at the service point nearest to the resident so that an approval
 * can never be followed by "the goods are gone". Requests for items that are not stocked are
 * left for an operator.
 */
@Service
public class AutoDecisionService {
    static final String MANUAL_NOTE = "库存中暂无此物资，已转人工处理，工作人员会尽快跟进。";

    private final JdbcClient jdbc;
    private final SupplyRequestRepository requests;
    private final RequestService requestService;
    private final ReservationService reservations;
    private final SystemActor system;

    public AutoDecisionService(JdbcClient jdbc, SupplyRequestRepository requests, RequestService requestService,
                               ReservationService reservations, SystemActor system) {
        this.jdbc = jdbc;
        this.requests = requests;
        this.requestService = requestService;
        this.reservations = reservations;
        this.system = system;
    }

    @Transactional
    public SupplyRequestView decide(long requestId) {
        SupplyRequestView request = requestService.get(requestId);
        if (request.status() != RequestStatus.SUBMITTED) return request;
        if (request.inventoryItemId() == null) {
            requests.recordDecision(requestId, MANUAL_NOTE);
            return requestService.get(requestId);
        }

        List<Candidate> candidates = candidatesFor(request.inventoryItemId());
        long actor = system.id();
        requestService.transition(requestId, actor,
            new RequestService.TransitionRequest(RequestStatus.UNDER_REVIEW, null, null), false);

        boolean pickup = request.fulfillmentMethod() == FulfillmentMethod.PICKUP;
        Instant start = request.preferredStart();
        Instant end = request.preferredEnd();
        // A booked pickup needs a point that is open during the slot.
        Instant[] bookedWindow = pickup && start != null ? new Instant[] {start, end} : null;
        Candidate chosen = choose(candidates, request.quantity(),
            request.latitude().doubleValue(), request.longitude().doubleValue(), bookedWindow);
        if (chosen == null && bookedWindow != null
            && choose(candidates, request.quantity(), request.latitude().doubleValue(), request.longitude().doubleValue(), null) != null) {
            requests.recordDecision(requestId, "所选时段内没有既营业又有货的服务点，已自动拒绝。请改选其他时段，或选择“尽快”后重新提交。");
            requestService.transition(requestId, actor,
                new RequestService.TransitionRequest(RequestStatus.REJECTED, null, null), true);
            return requestService.get(requestId);
        }
        if (chosen == null) {
            int mostAvailable = candidates.stream().mapToInt(Candidate::freeQuantity).max().orElse(0);
            String name = candidates.isEmpty() ? "该物资" : candidates.getFirst().name();
            String unit = candidates.isEmpty() ? "" : candidates.getFirst().unit();
            requests.recordDecision(requestId, "库存不足，已自动拒绝：" + name + " 目前单个服务点最多可领 "
                + Math.max(mostAvailable, 0) + unit + "。可减少数量或改选其他物资后重新提交。");
            requestService.transition(requestId, actor,
                new RequestService.TransitionRequest(RequestStatus.REJECTED, null, null), true);
            return requestService.get(requestId);
        }

        requestService.transition(requestId, actor,
            new RequestService.TransitionRequest(RequestStatus.APPROVED, null, null), true);
        reservations.reserve(request.residentId(),
            new ReservationService.ReserveInput(requestId, chosen.itemId(), request.quantity()));
        if (pickup) {
            requestService.transition(requestId, actor,
                new RequestService.TransitionRequest(RequestStatus.SCHEDULED, chosen.servicePointId(), null), false);
            String when = start == null ? "营业时间 " + chosen.hours()
                : describe(ServiceWindow.overlapWithHours(start, end, chosen.opensAt(), chosen.closesAt()));
            requests.recordDecision(requestId, "已自动批准：请在 " + when + " 到" + chosen.servicePointName()
                + "，出示领取码领取（领取码见“我的领取码”）。");
        } else {
            String when = start == null ? "系统正在安排补给车取货配送" : "补给车将于 " + ServiceWindow.describe(start, end) + " 送达";
            requests.recordDecision(requestId, "已自动批准：物资已在" + chosen.servicePointName()
                + "锁定，" + when + "，出发后可在地图查看车辆位置。");
        }
        return requestService.get(requestId);
    }

    /**
     * Locks the stock rows a submission for this item may reserve, before the request row is
     * written. Inserting the request takes a shared foreign-key lock on the item's row; taking the
     * exclusive lock only afterwards lets two residents ordering the same item deadlock.
     */
    public void lockStock(long itemId) {
        candidatesFor(itemId);
    }

    /**
     * Same free item (name, unit, category) at every active service point, its stock rows locked
     * for this decision. Only inventory rows are locked, always in id order, so decisions on
     * different items never wait on each other and decisions on the same item simply queue.
     */
    private List<Candidate> candidatesFor(long itemId) {
        Optional<ItemKey> key = jdbc.sql("SELECT name, unit, category FROM inventory_items WHERE id=:itemId")
            .param("itemId", itemId)
            .query((rs, n) -> new ItemKey(rs.getString("name"), rs.getString("unit"), rs.getString("category")))
            .optional();
        if (key.isEmpty()) return List.of();
        // A locking read returns the latest committed quantities, never this transaction's snapshot.
        List<LockedStock> stock = jdbc.sql("""
                SELECT id, service_point_id, available_quantity - reserved_quantity AS free_quantity
                FROM inventory_items
                WHERE name = :name AND unit = :unit AND category = :category AND unit_price_fen = 0
                ORDER BY id FOR UPDATE
                """).param("name", key.get().name()).param("unit", key.get().unit())
            .param("category", key.get().category())
            .query((rs, n) -> new LockedStock(rs.getLong("id"), rs.getLong("service_point_id"), rs.getInt("free_quantity")))
            .list();
        Map<Long, PointRow> points = new HashMap<>();
        jdbc.sql("SELECT id, name, latitude, longitude, opens_at, closes_at FROM service_points WHERE status = 'ACTIVE'")
            .query((rs, n) -> new PointRow(rs.getLong("id"), rs.getString("name"), rs.getDouble("latitude"),
                rs.getDouble("longitude"), rs.getString("opens_at"), rs.getString("closes_at"),
                rs.getObject("opens_at", LocalTime.class), rs.getObject("closes_at", LocalTime.class)))
            .list().forEach(point -> points.put(point.id(), point));
        return stock.stream().filter(row -> points.containsKey(row.servicePointId())).map(row -> {
            PointRow point = points.get(row.servicePointId());
            return new Candidate(row.itemId(), key.get().name(), key.get().unit(), row.freeQuantity(), point.id(),
                point.name(), point.latitude(), point.longitude(), clock(point.opens()) + "–" + clock(point.closes()),
                point.opensAt(), point.closesAt());
        }).toList();
    }

    /** Nearest point with enough stock; with a window, only points open during part of it. */
    static Candidate choose(List<Candidate> candidates, int quantity, double latitude, double longitude, Instant[] window) {
        return candidates.stream().filter(candidate -> candidate.freeQuantity() >= quantity)
            .filter(candidate -> window == null
                || ServiceWindow.overlapWithHours(window[0], window[1], candidate.opensAt(), candidate.closesAt()) != null)
            .min(Comparator.comparingDouble(candidate -> GeoMath.distanceMeters(latitude, longitude,
                candidate.latitude(), candidate.longitude())))
            .orElse(null);
    }

    private static String describe(Instant[] window) {
        return ServiceWindow.describe(window[0], window[1]);
    }

    private static String clock(String time) {
        return time == null ? "" : time.substring(0, Math.min(5, time.length()));
    }

    record ItemKey(String name, String unit, String category) {
    }
    record LockedStock(long itemId, long servicePointId, int freeQuantity) {
    }
    record PointRow(long id, String name, double latitude, double longitude, String opens, String closes,
                    LocalTime opensAt, LocalTime closesAt) {
    }
    record Candidate(long itemId, String name, String unit, int freeQuantity, long servicePointId,
                     String servicePointName, double latitude, double longitude, String hours,
                     LocalTime opensAt, LocalTime closesAt) {
    }
}
