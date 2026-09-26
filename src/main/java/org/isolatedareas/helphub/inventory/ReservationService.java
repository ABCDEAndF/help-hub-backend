package org.isolatedareas.helphub.inventory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.List;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.isolatedareas.helphub.audit.AuditService;
import org.isolatedareas.helphub.domain.FulfillmentMethod;
import org.isolatedareas.helphub.events.OutboxService;
import org.isolatedareas.helphub.impact.StockoutAttempt;
import org.isolatedareas.helphub.requests.SupplyRequestRepository;
import org.isolatedareas.helphub.requests.SupplyRequestView;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ReservationService {
    private static final Duration HOLD_DURATION = Duration.ofHours(48);
    private final JdbcClient jdbc;
    private final InventoryRepository inventory;
    private final SupplyRequestRepository requests;
    private final StringRedisTemplate redis;
    private final OutboxService outbox;
    private final AuditService audit;
    private final ApplicationEventPublisher events;
    private final String pickupCodeSecret;

    public ReservationService(JdbcClient jdbc, InventoryRepository inventory, SupplyRequestRepository requests,
                              StringRedisTemplate redis, OutboxService outbox, AuditService audit,
                              ApplicationEventPublisher events,
                              @Value("${app.pickup-code-secret}") String pickupCodeSecret) {
        this.jdbc = jdbc;
        this.inventory = inventory;
        this.requests = requests;
        this.redis = redis;
        this.outbox = outbox;
        this.audit = audit;
        this.events = events;
        if (pickupCodeSecret == null || pickupCodeSecret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalArgumentException("PICKUP_CODE_SECRET must contain at least 32 bytes");
        }
        this.pickupCodeSecret = pickupCodeSecret;
    }

    @Transactional
    public ReservationReceipt reserve(long residentId, ReserveInput input) {
        SupplyRequestView request = requests.findOwned(input.requestId(), residentId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Supply request not found"));
        if (request.status() != org.isolatedareas.helphub.domain.RequestStatus.APPROVED &&
            request.status() != org.isolatedareas.helphub.domain.RequestStatus.SCHEDULED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Request must be approved before reservation");
        }
        InventoryItemView item = inventory.lock(input.inventoryItemId())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Inventory item not found"));
        if (!item.category().equalsIgnoreCase(request.category())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Reserved inventory category must match the approved request");
        }
        if (request.fulfillmentMethod() == FulfillmentMethod.PICKUP && request.assignedServicePointId() != null
            && request.assignedServicePointId() != item.servicePointId()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Pickup inventory must come from the assigned service point");
        }
        if (item.freeQuantity() < input.quantity()) {
            events.publishEvent(new StockoutAttempt(residentId, request.id(), item.id(),
                input.quantity(), item.freeQuantity()));
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Insufficient inventory");
        }
        inventory.hold(item.id(), input.quantity());
        Instant expiresAt = Instant.now().plus(HOLD_DURATION);
        org.springframework.jdbc.support.GeneratedKeyHolder keys = new org.springframework.jdbc.support.GeneratedKeyHolder();
        jdbc.sql("""
                INSERT INTO reservations
                  (request_id, resident_id, inventory_item_id, quantity, pickup_code_hash, expires_at)
                VALUES (:requestId, :residentId, :itemId, :quantity, :codeHash, :expiresAt)
                """)
            .param("requestId", request.id()).param("residentId", residentId).param("itemId", item.id())
            .param("quantity", input.quantity()).param("codeHash", "pending")
            .param("expiresAt", Timestamp.from(expiresAt)).update(keys);
        long reservationId = keys.getKey().longValue();
        String pickupCode = pickupCode(reservationId, residentId);
        jdbc.sql("UPDATE reservations SET pickup_code_hash=:hash WHERE id=:id")
            .param("hash", hash(pickupCode)).param("id", reservationId).update();
        afterCommit(() -> redis.opsForValue().set("reservation:" + reservationId, "HELD", HOLD_DURATION));
        audit.record(residentId, "RESERVATION_HELD", "RESERVATION", reservationId, null,
            Map.of("requestId", request.id(), "itemId", item.id(), "quantity", input.quantity(), "expiresAt", expiresAt));
        outbox.append("RESERVATION", reservationId, "ReservationHeld", "notification.send",
            Map.of("eventId", "reservation-held-" + reservationId, "recipientUserId", residentId,
                "template", "RESERVATION_HELD", "reservationId", reservationId));
        int amountFen = Math.multiplyExact(item.unitPriceFen(), input.quantity());
        return new ReservationReceipt(reservationId, item.name(), input.quantity(), amountFen == 0 ? pickupCode : null, expiresAt,
            amountFen > 0, amountFen);
    }

    public List<ReservationView> list(long residentId) {
        return jdbc.sql("""
                SELECT r.id, r.request_id, r.quantity, r.status, r.expires_at, r.created_at,
                  i.name AS item_name, i.unit_price_fen, s.name AS service_point_name,
                  s.address AS service_point_address, p.id AS payment_id, p.status AS payment_status
                FROM reservations r
                JOIN inventory_items i ON i.id=r.inventory_item_id
                JOIN service_points s ON s.id=i.service_point_id
                LEFT JOIN payment_orders p ON p.reservation_id=r.id
                WHERE r.resident_id=:residentId
                ORDER BY r.created_at DESC LIMIT 100
                """).param("residentId", residentId)
            .query((rs, n) -> new ReservationView(rs.getLong("id"), rs.getLong("request_id"),
                rs.getString("item_name"), rs.getInt("quantity"), rs.getString("status"),
                rs.getTimestamp("expires_at").toInstant(), rs.getTimestamp("created_at").toInstant(),
                rs.getString("service_point_name"), rs.getString("service_point_address"),
                rs.getInt("unit_price_fen"), rs.getInt("unit_price_fen") * rs.getInt("quantity"),
                (Long) rs.getObject("payment_id"), rs.getString("payment_status"),
                pickupCodeVisible(rs.getString("status"), rs.getInt("unit_price_fen"))
                    ? pickupCode(rs.getLong("id"), residentId) : null)).list();
    }

    @Transactional
    public void collect(long operatorId, long reservationId, String pickupCode) {
        ReservationRow row = jdbc.sql("""
                SELECT r.id, r.resident_id, r.inventory_item_id, r.quantity, r.pickup_code_hash, r.status,
                  i.unit_price_fen
                FROM reservations r JOIN inventory_items i ON i.id=r.inventory_item_id
                WHERE r.id=:id FOR UPDATE
                """)
            .param("id", reservationId)
            .query((rs, n) -> new ReservationRow(rs.getLong("id"), rs.getLong("resident_id"),
                rs.getLong("inventory_item_id"), rs.getInt("quantity"), rs.getString("pickup_code_hash"),
                rs.getString("status"), rs.getInt("unit_price_fen"))).optional()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Reservation not found"));
        if (!"HELD".equals(row.status()) && !"CONFIRMED".equals(row.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Reservation is not collectible");
        }
        if (row.unitPriceFen() > 0 && !"CONFIRMED".equals(row.status())) {
            throw new ResponseStatusException(HttpStatus.PAYMENT_REQUIRED, "Payment is required before collection");
        }
        if (!MessageDigest.isEqual(row.codeHash().getBytes(StandardCharsets.UTF_8),
            hash(pickupCode).getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Invalid pickup code");
        }
        inventory.collect(row.inventoryItemId(), row.quantity());
        jdbc.sql("UPDATE reservations SET status='COLLECTED' WHERE id=:id")
            .param("id", reservationId).update();
        afterCommit(() -> redis.delete("reservation:" + reservationId));
        audit.record(operatorId, "RESERVATION_COLLECTED", "RESERVATION", reservationId, row,
            Map.of("status", "COLLECTED"));
    }

    @Scheduled(fixedDelay = 60_000)
    @Transactional
    public void expireHolds() {
        var expired = jdbc.sql("""
                SELECT id, inventory_item_id, quantity FROM reservations
                WHERE status='HELD' AND expires_at < CURRENT_TIMESTAMP(3) FOR UPDATE
                """)
            .query((rs, n) -> new ExpiredRow(rs.getLong("id"), rs.getLong("inventory_item_id"), rs.getInt("quantity")))
            .list();
        for (ExpiredRow row : expired) {
            inventory.release(row.inventoryItemId(), row.quantity());
            jdbc.sql("UPDATE reservations SET status='EXPIRED' WHERE id=:id AND status='HELD'")
                .param("id", row.id()).update();
            afterCommit(() -> redis.delete("reservation:" + row.id()));
        }
    }

    private void afterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { action.run(); }
        });
    }

    private String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private boolean pickupCodeVisible(String status, int unitPriceFen) {
        return "CONFIRMED".equals(status) || (unitPriceFen == 0 && "HELD".equals(status));
    }

    private String pickupCode(long reservationId, long residentId) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(pickupCodeSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal((reservationId + ":" + residentId).getBytes(StandardCharsets.UTF_8));
            int value = java.nio.ByteBuffer.wrap(digest).getInt() & 0x7fffffff;
            return String.format("%06d", value % 1_000_000);
        } catch (java.security.GeneralSecurityException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    public record ReserveInput(long requestId, long inventoryItemId,
                               @jakarta.validation.constraints.Min(1)
                               @jakarta.validation.constraints.Max(100) int quantity) {
    }
    public record ReservationReceipt(long reservationId, String itemName, int quantity, String pickupCode,
                                     Instant expiresAt, boolean paymentRequired, int amountFen) {
    }
    public record ReservationView(long reservationId, long requestId, String itemName, int quantity,
                                  String status, Instant expiresAt, Instant createdAt,
                                  String servicePointName, String servicePointAddress, int unitPriceFen,
                                  int amountFen, Long paymentId, String paymentStatus, String pickupCode) {
    }
    record ReservationRow(long id, long residentId, long inventoryItemId, int quantity, String codeHash,
                          String status, int unitPriceFen) {
    }
    record ExpiredRow(long id, long inventoryItemId, int quantity) {
    }
}
