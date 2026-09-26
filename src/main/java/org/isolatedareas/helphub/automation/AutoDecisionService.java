package org.isolatedareas.helphub.automation;

import java.util.Comparator;
import java.util.List;
import org.isolatedareas.helphub.domain.FulfillmentMethod;
import org.isolatedareas.helphub.domain.RequestStatus;
import org.isolatedareas.helphub.geo.GeoMath;
import org.isolatedareas.helphub.inventory.ReservationService;
import org.isolatedareas.helphub.requests.RequestService;
import org.isolatedareas.helphub.requests.SupplyRequestRepository;
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

        Candidate chosen = choose(candidates, request.quantity(),
            request.latitude().doubleValue(), request.longitude().doubleValue());
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
        if (request.fulfillmentMethod() == FulfillmentMethod.PICKUP) {
            requestService.transition(requestId, actor,
                new RequestService.TransitionRequest(RequestStatus.SCHEDULED, chosen.servicePointId(), null), false);
            requests.recordDecision(requestId, "已自动批准：请在营业时间 " + chosen.hours() + " 到"
                + chosen.servicePointName() + "，出示领取码领取（领取码见“我的预约”，48 小时内有效）。");
        } else {
            requests.recordDecision(requestId, "已自动批准：物资已在" + chosen.servicePointName()
                + "锁定，系统正在安排补给车取货配送，可在地图查看车辆位置。");
        }
        return requestService.get(requestId);
    }

    /** Same free item (name, unit, category) at every active service point, locked for this decision. */
    private List<Candidate> candidatesFor(long itemId) {
        return jdbc.sql("""
                SELECT i.id, i.name, i.unit, i.available_quantity - i.reserved_quantity AS free_quantity,
                  s.id AS point_id, s.name AS point_name, s.latitude, s.longitude, s.opens_at, s.closes_at
                FROM inventory_items i
                JOIN inventory_items ref ON ref.id = :itemId
                JOIN service_points s ON s.id = i.service_point_id
                WHERE i.name = ref.name AND i.unit = ref.unit AND i.category = ref.category
                  AND i.unit_price_fen = 0 AND s.status = 'ACTIVE'
                ORDER BY i.id FOR UPDATE
                """).param("itemId", itemId)
            .query((rs, n) -> new Candidate(rs.getLong("id"), rs.getString("name"), rs.getString("unit"),
                rs.getInt("free_quantity"), rs.getLong("point_id"), rs.getString("point_name"),
                rs.getDouble("latitude"), rs.getDouble("longitude"),
                clock(rs.getString("opens_at")) + "–" + clock(rs.getString("closes_at")))).list();
    }

    static Candidate choose(List<Candidate> candidates, int quantity, double latitude, double longitude) {
        return candidates.stream().filter(candidate -> candidate.freeQuantity() >= quantity)
            .min(Comparator.comparingDouble(candidate -> GeoMath.distanceMeters(latitude, longitude,
                candidate.latitude(), candidate.longitude())))
            .orElse(null);
    }

    private static String clock(String time) {
        return time == null ? "" : time.substring(0, Math.min(5, time.length()));
    }

    record Candidate(long itemId, String name, String unit, int freeQuantity, long servicePointId,
                     String servicePointName, double latitude, double longitude, String hours) {
    }
}
