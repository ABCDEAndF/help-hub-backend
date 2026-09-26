package org.isolatedareas.helphub.automation;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import org.isolatedareas.helphub.domain.RequestStatus;
import org.isolatedareas.helphub.inventory.ReservationCollected;
import org.isolatedareas.helphub.inventory.ReservationExpired;
import org.isolatedareas.helphub.requests.RequestService;
import org.isolatedareas.helphub.requests.SupplyRequestRepository;
import org.isolatedareas.helphub.requests.SupplyRequestView;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Closes requests without an operator: a handed-over reservation completes its request, and an
 * automatically approved request whose pickup code lapsed unused is cancelled (its stock was
 * already released by the expiry). Runs synchronously inside the publishing transaction.
 */
@Component
public class RequestLifecycleListener {
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("M月d日 HH:mm").withZone(ZoneId.of("Asia/Shanghai"));

    private final RequestService requestService;
    private final SupplyRequestRepository requests;
    private final SystemActor system;
    private final JdbcClient jdbc;

    public RequestLifecycleListener(RequestService requestService, SupplyRequestRepository requests,
                                    SystemActor system, JdbcClient jdbc) {
        this.requestService = requestService;
        this.requests = requests;
        this.system = system;
        this.jdbc = jdbc;
    }

    @EventListener
    public void onCollected(ReservationCollected event) {
        SupplyRequestView request = requestService.get(event.requestId());
        boolean open = request.status() == RequestStatus.APPROVED || request.status() == RequestStatus.SCHEDULED;
        if (!open || outstanding(event.requestId(), "'HELD','CONFIRMED'") > 0) return;
        requestService.transition(event.requestId(), event.actorId(),
            new RequestService.TransitionRequest(RequestStatus.FULFILLED, null, null), true);
        requests.recordDecision(event.requestId(), "已于 " + CLOCK.format(Instant.now()) + " 领取，本次服务已完成。");
    }

    @EventListener
    public void onExpired(ReservationExpired event) {
        SupplyRequestView request = requestService.get(event.requestId());
        boolean open = request.status() == RequestStatus.APPROVED || request.status() == RequestStatus.SCHEDULED;
        // Operator-approved requests keep their approval so the resident can simply reserve again.
        if (!open || request.inventoryItemId() == null
            || outstanding(event.requestId(), "'HELD','CONFIRMED','COLLECTED'") > 0) return;
        requestService.transition(event.requestId(), system.id(),
            new RequestService.TransitionRequest(RequestStatus.CANCELLED, null, null), true);
        requests.recordDecision(event.requestId(),
            "领取码超过 48 小时未使用，需求已自动取消，物资已释放给其他居民；如仍需要请重新提交。");
    }

    private int outstanding(long requestId, String statuses) {
        return jdbc.sql("SELECT COUNT(*) FROM reservations WHERE request_id=:id AND status IN (" + statuses + ")")
            .param("id", requestId).query(Integer.class).single();
    }
}
