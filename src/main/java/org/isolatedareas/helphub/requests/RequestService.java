package org.isolatedareas.helphub.requests;

import java.util.Map;
import org.isolatedareas.helphub.audit.AuditService;
import org.isolatedareas.helphub.domain.FulfillmentMethod;
import org.isolatedareas.helphub.domain.RequestStatus;
import org.isolatedareas.helphub.events.OutboxService;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class RequestService {
    private final SupplyRequestRepository requests;
    private final OutboxService outbox;
    private final AuditService audit;
    private final JdbcClient jdbc;

    public RequestService(SupplyRequestRepository requests, OutboxService outbox, AuditService audit, JdbcClient jdbc) {
        this.requests = requests;
        this.outbox = outbox;
        this.audit = audit;
        this.jdbc = jdbc;
    }

    @Transactional
    public SupplyRequestView create(long residentId, CreateSupplyRequest input) {
        if (input.inventoryItemId() != null) {
            // A request for a stocked item always takes that item's category, whatever the client sent.
            String category = jdbc.sql("SELECT category FROM inventory_items WHERE id=:id")
                .param("id", input.inventoryItemId()).query(String.class).optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Inventory item not found"));
            input = input.withCategory(category);
        }
        long id = requests.insert(residentId, input);
        SupplyRequestView created = get(id);
        audit.record(residentId, "REQUEST_CREATED", "SUPPLY_REQUEST", id, null, created);
        outbox.append("SUPPLY_REQUEST", id, "SupplyRequestCreated", "notification.send",
            Map.of("eventId", "request-created-" + id, "requestId", id, "recipientUserId", residentId,
                "template", "REQUEST_RECEIVED", "requestNumber", created.residentNumber()));
        return created;
    }

    public SupplyRequestView get(long id) {
        return requests.findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Supply request not found"));
    }

    @Transactional
    public SupplyRequestView transition(long id, long actorId, TransitionRequest input) {
        return transition(id, actorId, input, true);
    }

    /** notifyResident=false is for intermediate automatic steps the resident need not hear about. */
    @Transactional
    public SupplyRequestView transition(long id, long actorId, TransitionRequest input, boolean notifyResident) {
        SupplyRequestView before = get(id);
        if (!isAllowed(before.status(), input.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Cannot transition from " + before.status() + " to " + input.status());
        }
        if (input.status() == RequestStatus.SCHEDULED &&
            ((input.servicePointId() == null) == (input.cartId() == null))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Scheduling requires exactly one service point or mobile cart");
        }
        if (input.status() == RequestStatus.SCHEDULED) {
            boolean pickup = before.fulfillmentMethod() == FulfillmentMethod.PICKUP;
            if (pickup && input.servicePointId() == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Pickup requests must be scheduled at a service point");
            }
            if (!pickup && input.cartId() == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Delivery requests must be scheduled on a mobile cart");
            }
        }
        long version = requests.version(id);
        if (requests.transition(id, version, before.status(), input.status(), actorId,
            input.servicePointId(), input.cartId()) != 1) {
            throw new OptimisticLockingFailureException("Supply request changed concurrently");
        }
        if (input.status() == RequestStatus.CANCELLED) releaseHeldStock(id);
        SupplyRequestView after = get(id);
        audit.record(actorId, "REQUEST_STATUS_CHANGED", "SUPPLY_REQUEST", id, before, after);
        if (notifyResident) {
            outbox.append("SUPPLY_REQUEST", id, "SupplyRequestStatusChanged", "notification.send",
                Map.of("eventId", "request-status-" + id + "-" + input.status(), "requestId", id,
                    "recipientUserId", before.residentId(), "template", "REQUEST_" + input.status(),
                    "requestNumber", before.residentNumber()));
        }
        if (input.status() == RequestStatus.FULFILLED) {
            jdbc.sql("""
                    INSERT INTO impact_events (event_type, resident_id, request_id, metadata)
                    VALUES ('REQUEST_FULFILLED', :residentId, :requestId, JSON_OBJECT('category', :category, 'quantity', :quantity))
                    """)
                .param("residentId", before.residentId()).param("requestId", id)
                .param("category", before.category()).param("quantity", before.quantity()).update();
        }
        return after;
    }

    /** A cancelled request must not keep stock locked until its reservation expires. */
    private void releaseHeldStock(long requestId) {
        jdbc.sql("""
                UPDATE inventory_items i JOIN reservations r ON r.inventory_item_id = i.id
                SET i.reserved_quantity = i.reserved_quantity - r.quantity, i.version = i.version + 1
                WHERE r.request_id = :id AND r.status = 'HELD'
                """).param("id", requestId).update();
        jdbc.sql("UPDATE reservations SET status='CANCELLED' WHERE request_id=:id AND status='HELD'")
            .param("id", requestId).update();
    }

    private boolean isAllowed(RequestStatus from, RequestStatus to) {
        return switch (from) {
            case SUBMITTED -> to == RequestStatus.UNDER_REVIEW || to == RequestStatus.CANCELLED;
            case UNDER_REVIEW -> to == RequestStatus.APPROVED || to == RequestStatus.REJECTED;
            // FULFILLED straight from APPROVED: the supplies were already handed over against a pickup code.
            case APPROVED -> to == RequestStatus.SCHEDULED || to == RequestStatus.FULFILLED || to == RequestStatus.CANCELLED;
            case SCHEDULED -> to == RequestStatus.FULFILLED || to == RequestStatus.CANCELLED;
            case FULFILLED, REJECTED, CANCELLED -> false;
        };
    }

    public record TransitionRequest(RequestStatus status, Long servicePointId, Long cartId) {
    }
}
