package org.isolatedareas.helphub.payments;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.isolatedareas.helphub.audit.AuditService;
import org.isolatedareas.helphub.events.OutboxService;
import org.isolatedareas.helphub.inventory.InventoryRepository;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PaymentService {
    private final JdbcClient jdbc;
    private final WechatPayGateway gateway;
    private final PaymentVerifier verifier;
    private final TransactionTemplate transactions;
    private final InventoryRepository inventory;
    private final OutboxService outbox;
    private final AuditService audit;

    public PaymentService(JdbcClient jdbc, WechatPayGateway gateway, PaymentVerifier verifier,
                          TransactionTemplate transactions,
                          InventoryRepository inventory, OutboxService outbox, AuditService audit) {
        this.jdbc = jdbc;
        this.gateway = gateway;
        this.verifier = verifier;
        this.transactions = transactions;
        this.inventory = inventory;
        this.outbox = outbox;
        this.audit = audit;
    }

    /** Commits the local order before making the remote call, then stores the signed mini-program parameters. */
    public PaymentStart start(long residentId, long reservationId) {
        if (!gateway.configured()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "WeChat Pay merchant credentials are not configured");
        }
        PaymentSeed seed = transactions.execute(status -> createOrLoad(residentId, reservationId));
        if (seed == null) throw new IllegalStateException("Payment transaction did not return an order");
        if ("SUCCESS".equals(seed.status())) return new PaymentStart(seed.id(), seed.outTradeNo(), seed.amountFen(),
            seed.status(), null);
        if ("CLOSED".equals(seed.status()) || "FAILED".equals(seed.status())
            || "REFUNDING".equals(seed.status()) || "REFUNDED".equals(seed.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "This payment order can no longer be submitted");
        }
        if (Instant.now().isAfter(seed.expiresAt())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Reservation payment window has expired");
        }
        WechatPayGateway.PrepayData parameters = gateway.prepay(seed.outTradeNo(), seed.openId(),
            "邻需通 - " + seed.itemName(), seed.amountFen(), seed.expiresAt());
        transactions.executeWithoutResult(status -> jdbc.sql("""
                UPDATE payment_orders SET status='PREPAY', wechat_prepay_id=:prepay
                WHERE id=:id AND status IN ('CREATED','PREPAY')
                """).param("prepay", parameters.packageValue()).param("id", seed.id()).update());
        return new PaymentStart(seed.id(), seed.outTradeNo(), seed.amountFen(), "PREPAY", parameters);
    }

    private PaymentSeed createOrLoad(long residentId, long reservationId) {
        ReservationForPayment reservation = jdbc.sql("""
                SELECT r.id, r.status, r.quantity, r.expires_at, i.name, i.unit_price_fen, u.wechat_open_id
                FROM reservations r JOIN inventory_items i ON i.id=r.inventory_item_id
                JOIN users u ON u.id=r.resident_id
                WHERE r.id=:id AND r.resident_id=:residentId FOR UPDATE
                """).param("id", reservationId).param("residentId", residentId)
            .query((rs, n) -> new ReservationForPayment(rs.getLong("id"), rs.getString("status"),
                rs.getInt("quantity"), rs.getTimestamp("expires_at").toInstant(), rs.getString("name"),
                rs.getInt("unit_price_fen"), rs.getString("wechat_open_id"))).optional()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Reservation not found"));
        if (!"HELD".equals(reservation.status()) && !"CONFIRMED".equals(reservation.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Reservation cannot be paid");
        }
        int amount = Math.multiplyExact(reservation.quantity(), reservation.unitPriceFen());
        if (amount <= 0) throw new ResponseStatusException(HttpStatus.CONFLICT, "This reservation is fully subsidized");
        if (reservation.openId() == null || reservation.openId().isBlank()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A real WeChat login is required for payment");
        }
        String outTradeNo = "HH" + UUID.randomUUID().toString().replace("-", "").substring(0, 28);
        jdbc.sql("""
                INSERT IGNORE INTO payment_orders
                  (out_trade_no, reservation_id, resident_id, amount_fen, expires_at)
                VALUES (:tradeNo, :reservationId, :residentId, :amount, :expiresAt)
                """).param("tradeNo", outTradeNo).param("reservationId", reservation.id())
            .param("residentId", residentId).param("amount", amount)
            .param("expiresAt", Timestamp.from(reservation.expiresAt())).update();
        return jdbc.sql("""
                SELECT p.id, p.out_trade_no, p.amount_fen, p.status, p.expires_at,
                  u.wechat_open_id, i.name
                FROM payment_orders p JOIN users u ON u.id=p.resident_id
                JOIN reservations r ON r.id=p.reservation_id
                JOIN inventory_items i ON i.id=r.inventory_item_id
                WHERE p.reservation_id=:reservationId
                """).param("reservationId", reservation.id())
            .query((rs, n) -> new PaymentSeed(rs.getLong("id"), rs.getString("out_trade_no"),
                rs.getInt("amount_fen"), rs.getString("status"), rs.getTimestamp("expires_at").toInstant(),
                rs.getString("wechat_open_id"), rs.getString("name"))).single();
    }

    @Transactional
    public void applyPayment(WechatPayGateway.PaymentNotice notice) {
        PaymentCallbackRow row = jdbc.sql("""
                SELECT p.id, p.reservation_id, p.resident_id, p.amount_fen, p.currency, p.status,
                  u.wechat_open_id
                FROM payment_orders p JOIN users u ON u.id=p.resident_id
                WHERE p.out_trade_no=:tradeNo FOR UPDATE
                """).param("tradeNo", notice.outTradeNo())
            .query((rs, n) -> new PaymentCallbackRow(rs.getLong("id"), rs.getLong("reservation_id"),
                rs.getLong("resident_id"), rs.getInt("amount_fen"), rs.getString("currency"),
                rs.getString("status"), rs.getString("wechat_open_id"))).optional()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Payment order not found"));
        verifier.verify(new PaymentVerifier.ExpectedPayment(row.amountFen(), row.currency(), row.openId(),
            gateway.appId(), gateway.merchantId()), notice);
        if ("SUCCESS".equals(row.status()) || "REFUNDING".equals(row.status())
            || "REFUNDED".equals(row.status())) return;
        jdbc.sql("""
                UPDATE payment_orders SET status='SUCCESS', wechat_transaction_id=:transactionId,
                  paid_at=CURRENT_TIMESTAMP(3) WHERE id=:id
                """).param("transactionId", notice.transactionId()).param("id", row.id()).update();
        jdbc.sql("""
                UPDATE reservations SET status='CONFIRMED', expires_at=:pickupExpiry
                WHERE id=:id AND status='HELD'
                """).param("pickupExpiry", Timestamp.from(Instant.now().plus(24, ChronoUnit.HOURS)))
            .param("id", row.reservationId()).update();
        audit.record(row.residentId(), "PAYMENT_SUCCEEDED", "PAYMENT", row.id(), null,
            Map.of("outTradeNo", notice.outTradeNo(), "amountFen", row.amountFen()));
        outbox.append("PAYMENT", row.id(), "PaymentSucceeded", "notification.send",
            Map.of("eventId", "payment-success-" + row.id(), "recipientUserId", row.residentId(),
                "template", "PAYMENT_SUCCEEDED", "reservationId", row.reservationId()));
    }

    public RefundView requestRefund(Long actorId, long paymentId, boolean administrator, String reason) {
        RefundSeed seed = transactions.execute(status -> prepareRefund(actorId, paymentId, administrator, reason));
        if (seed == null) throw new IllegalStateException("Refund transaction did not return a record");
        try {
            WechatPayGateway.RefundData remote = gateway.refund(seed.outTradeNo(), seed.outRefundNo(),
                seed.amountFen(), seed.reason());
            verifyRefundResult(new PendingRefund(seed.id(), seed.outRefundNo(), seed.amountFen(), 0,
                seed.outTradeNo(), seed.amountFen()), remote);
            transactions.executeWithoutResult(status -> jdbc.sql("""
                    UPDATE payment_refunds SET status=:status, wechat_refund_id=:refundId,
                      next_query_at=CASE WHEN :status='PROCESSING'
                        THEN DATE_ADD(CURRENT_TIMESTAMP(3), INTERVAL 1 MINUTE) ELSE NULL END
                    WHERE id=:id
                    """).param("status", normalizeRefundStatus(remote.status()))
                .param("refundId", remote.refundId()).param("id", seed.id()).update());
            if ("SUCCESS".equals(remote.status())) {
                transactions.executeWithoutResult(status ->
                    finalizeRefund(seed.outRefundNo(), remote.refundId(), seed.amountFen()));
            } else if ("CLOSED".equals(remote.status()) || "ABNORMAL".equals(remote.status())) {
                transactions.executeWithoutResult(status -> jdbc.sql("""
                        UPDATE payment_orders SET status='SUCCESS'
                        WHERE id=:id AND status='REFUNDING'
                        """).param("id", seed.paymentId()).update());
            }
            return new RefundView(seed.id(), seed.outRefundNo(), seed.amountFen(), remote.status());
        } catch (RuntimeException error) {
            // The request may have reached WeChat before the connection failed. Keep the same refund
            // number in PROCESSING and reconcile it; generating another number could double-refund.
            transactions.executeWithoutResult(status -> jdbc.sql("""
                    UPDATE payment_refunds SET next_query_at=DATE_ADD(CURRENT_TIMESTAMP(3), INTERVAL 1 MINUTE)
                    WHERE id=:id AND status='PROCESSING'
                    """).param("id", seed.id()).update());
            throw error;
        }
    }

    private RefundSeed prepareRefund(Long actorId, long paymentId, boolean administrator, String reason) {
        RefundPaymentRow payment = jdbc.sql("""
                SELECT p.id, p.out_trade_no, p.resident_id, p.reservation_id, p.amount_fen, p.status,
                  r.status AS reservation_status
                FROM payment_orders p JOIN reservations r ON r.id=p.reservation_id
                WHERE p.id=:id FOR UPDATE
                """).param("id", paymentId)
            .query((rs, n) -> new RefundPaymentRow(rs.getLong("id"), rs.getString("out_trade_no"),
                rs.getLong("resident_id"), rs.getLong("reservation_id"), rs.getInt("amount_fen"),
                rs.getString("status"), rs.getString("reservation_status"))).optional()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Payment not found"));
        if (!administrator && (actorId == null || payment.residentId() != actorId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Payment not found");
        }
        if (!"SUCCESS".equals(payment.status()) || "COLLECTED".equals(payment.reservationStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Payment is not refundable");
        }
        String normalizedReason = reason == null || reason.isBlank() ? "用户取消未领取预约" : reason.trim();
        if (normalizedReason.length() > 255) normalizedReason = normalizedReason.substring(0, 255);
        String refundNo = "RF" + UUID.randomUUID().toString().replace("-", "").substring(0, 28);
        org.springframework.jdbc.support.GeneratedKeyHolder keys = new org.springframework.jdbc.support.GeneratedKeyHolder();
        jdbc.sql("""
                INSERT INTO payment_refunds (out_refund_no, payment_order_id, amount_fen, reason)
                VALUES (:refundNo, :paymentId, :amount, :reason)
                """).param("refundNo", refundNo).param("paymentId", payment.id())
            .param("amount", payment.amountFen()).param("reason", normalizedReason).update(keys);
        jdbc.sql("UPDATE payment_orders SET status='REFUNDING' WHERE id=:id")
            .param("id", payment.id()).update();
        audit.record(actorId, "REFUND_REQUESTED", "PAYMENT", payment.id(), null,
            Map.of("outRefundNo", refundNo, "amountFen", payment.amountFen()));
        return new RefundSeed(keys.getKey().longValue(), payment.id(), payment.outTradeNo(), refundNo,
            payment.amountFen(), normalizedReason);
    }

    @Transactional
    public void applyRefund(WechatPayGateway.RefundNotice notice) {
        if (notice.status() == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Refund status missing");
        jdbc.sql("""
                UPDATE payment_refunds SET status=:status, wechat_refund_id=:refundId,
                  success_at=CASE WHEN :status='SUCCESS' THEN CURRENT_TIMESTAMP(3) ELSE success_at END
                WHERE out_refund_no=:refundNo
                """).param("status", normalizeRefundStatus(notice.status())).param("refundId", notice.refundId())
            .param("refundNo", notice.outRefundNo()).update();
        if ("SUCCESS".equals(notice.status())) {
            if (notice.amountFen() == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Refund amount missing");
            finalizeRefund(notice.outRefundNo(), notice.refundId(), notice.amountFen().intValue());
        } else if ("CLOSED".equals(notice.status()) || "ABNORMAL".equals(notice.status())) {
            jdbc.sql("""
                    UPDATE payment_orders p JOIN payment_refunds r ON r.payment_order_id=p.id
                    SET p.status='SUCCESS' WHERE r.out_refund_no=:refundNo AND p.status='REFUNDING'
                    """).param("refundNo", notice.outRefundNo()).update();
        }
    }

    private void finalizeRefund(String refundNo, String refundId, int amountFen) {
        RefundFinalizeRow row = jdbc.sql("""
                SELECT r.id, r.amount_fen, p.id AS payment_id, p.reservation_id, p.resident_id,
                  rv.inventory_item_id, rv.quantity, rv.status AS reservation_status
                FROM payment_refunds r JOIN payment_orders p ON p.id=r.payment_order_id
                JOIN reservations rv ON rv.id=p.reservation_id
                WHERE r.out_refund_no=:refundNo FOR UPDATE
                """).param("refundNo", refundNo)
            .query((rs, n) -> new RefundFinalizeRow(rs.getLong("id"), rs.getInt("amount_fen"),
                rs.getLong("payment_id"), rs.getLong("reservation_id"), rs.getLong("resident_id"),
                rs.getLong("inventory_item_id"), rs.getInt("quantity"), rs.getString("reservation_status"))).optional()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Refund not found"));
        if (row.amountFen() != amountFen) throw new ResponseStatusException(HttpStatus.CONFLICT, "Refund amount mismatch");
        int changed = jdbc.sql("UPDATE payment_orders SET status='REFUNDED' WHERE id=:id AND status='REFUNDING'")
            .param("id", row.paymentId()).update();
        jdbc.sql("""
                UPDATE payment_refunds SET status='SUCCESS', wechat_refund_id=:refundId,
                  success_at=COALESCE(success_at, CURRENT_TIMESTAMP(3)) WHERE id=:id
                """).param("refundId", refundId).param("id", row.id()).update();
        if (changed == 1 && "CONFIRMED".equals(row.reservationStatus())) {
            inventory.release(row.inventoryItemId(), row.quantity());
            jdbc.sql("UPDATE reservations SET status='CANCELLED' WHERE id=:id AND status='CONFIRMED'")
                .param("id", row.reservationId()).update();
            outbox.append("PAYMENT", row.paymentId(), "PaymentRefunded", "notification.send",
                Map.of("eventId", "payment-refunded-" + row.paymentId(), "recipientUserId", row.residentId(),
                    "template", "PAYMENT_REFUNDED", "reservationId", row.reservationId()));
        }
    }

    public List<PaymentView> list(long requesterId, boolean administrator) {
        String where = administrator ? "" : " WHERE p.resident_id=:residentId";
        var spec = jdbc.sql("""
                SELECT p.id, p.out_trade_no, p.reservation_id, p.amount_fen, p.currency, p.status,
                  p.wechat_transaction_id, p.expires_at, p.paid_at, p.created_at, i.name AS item_name
                FROM payment_orders p JOIN reservations r ON r.id=p.reservation_id
                JOIN inventory_items i ON i.id=r.inventory_item_id
                """ + where + " ORDER BY p.created_at DESC LIMIT 200");
        if (!administrator) spec = spec.param("residentId", requesterId);
        return spec.query((rs, n) -> new PaymentView(rs.getLong("id"), rs.getString("out_trade_no"),
            rs.getLong("reservation_id"), rs.getString("item_name"), rs.getInt("amount_fen"),
            rs.getString("currency"), rs.getString("status"), rs.getString("wechat_transaction_id"),
            rs.getTimestamp("expires_at").toInstant(),
            rs.getTimestamp("paid_at") == null ? null : rs.getTimestamp("paid_at").toInstant(),
            rs.getTimestamp("created_at").toInstant())).list();
    }

    @Scheduled(fixedDelay = 60_000)
    public void closeExpiredOrders() {
        if (!gateway.configured()) return;
        List<ExpiredPayment> expired = jdbc.sql("""
                SELECT id, out_trade_no FROM payment_orders
                WHERE status IN ('CREATED','PREPAY') AND expires_at<CURRENT_TIMESTAMP(3) LIMIT 50
                """).query((rs, n) -> new ExpiredPayment(rs.getLong("id"), rs.getString("out_trade_no"))).list();
        for (ExpiredPayment order : expired) {
            try {
                WechatPayGateway.PaymentNotice remote = gateway.query(order.outTradeNo());
                if ("SUCCESS".equals(remote.tradeState())) {
                    transactions.executeWithoutResult(status -> applyPayment(remote));
                    continue;
                }
                if ("CLOSED".equals(remote.tradeState()) || "REVOKED".equals(remote.tradeState())
                    || "PAYERROR".equals(remote.tradeState())) {
                    jdbc.sql("UPDATE payment_orders SET status='CLOSED', closed_at=CURRENT_TIMESTAMP(3) WHERE id=:id AND status IN ('CREATED','PREPAY')")
                        .param("id", order.id()).update();
                    continue;
                }
                gateway.close(order.outTradeNo());
                jdbc.sql("UPDATE payment_orders SET status='CLOSED', closed_at=CURRENT_TIMESTAMP(3) WHERE id=:id AND status IN ('CREATED','PREPAY')")
                    .param("id", order.id()).update();
            } catch (RuntimeException ignored) {
                // A payment callback may be racing with closure; the verified callback remains authoritative.
            }
        }
    }

    /** A delayed success callback after inventory expiry is compensated with an automatic full refund. */
    @Scheduled(fixedDelay = 60_000, initialDelay = 30_000)
    public void refundPaymentsWhoseReservationsExpired() {
        if (!gateway.configured()) return;
        List<Long> paymentIds = jdbc.sql("""
                SELECT p.id FROM payment_orders p JOIN reservations r ON r.id=p.reservation_id
                WHERE p.status='SUCCESS' AND r.status IN ('EXPIRED','CANCELLED') LIMIT 50
                """).query(Long.class).list();
        for (Long paymentId : paymentIds) {
            try { requestRefund(null, paymentId, true, "预约已超时，系统自动退款"); }
            catch (RuntimeException ignored) {
                // The payment remains SUCCESS so a subsequent reconciliation pass retries the compensation.
            }
        }
    }

    /** Resolves ambiguous refund responses without ever issuing a second refund number. */
    @Scheduled(fixedDelay = 60_000, initialDelay = 45_000)
    public void reconcilePendingRefunds() {
        if (!gateway.configured()) return;
        List<PendingRefund> pending = jdbc.sql("""
                SELECT r.id, r.out_refund_no, r.amount_fen, r.query_attempts,
                  p.out_trade_no, p.amount_fen AS payment_amount
                FROM payment_refunds r JOIN payment_orders p ON p.id=r.payment_order_id
                WHERE r.status='PROCESSING' AND r.next_query_at<=CURRENT_TIMESTAMP(3)
                ORDER BY r.next_query_at LIMIT 50
                """).query((rs, n) -> new PendingRefund(rs.getLong("id"), rs.getString("out_refund_no"),
                    rs.getInt("amount_fen"), rs.getInt("query_attempts"), rs.getString("out_trade_no"),
                    rs.getInt("payment_amount"))).list();
        for (PendingRefund refund : pending) {
            try {
                WechatPayGateway.RefundData remote = gateway.queryRefund(refund.outRefundNo());
                verifyRefundResult(refund, remote);
                if ("SUCCESS".equals(remote.status())) {
                    transactions.executeWithoutResult(status ->
                        finalizeRefund(refund.outRefundNo(), remote.refundId(), refund.amountFen()));
                } else if ("CLOSED".equals(remote.status()) || "ABNORMAL".equals(remote.status())) {
                    transactions.executeWithoutResult(status -> {
                        jdbc.sql("""
                                UPDATE payment_refunds r JOIN payment_orders p ON p.id=r.payment_order_id
                                SET r.status=:status, r.wechat_refund_id=:refundId, r.next_query_at=NULL,
                                  p.status='SUCCESS'
                                WHERE r.id=:id AND r.status='PROCESSING'
                                """).param("status", remote.status()).param("refundId", remote.refundId())
                            .param("id", refund.id()).update();
                    });
                } else {
                    scheduleRefundQuery(refund.id(), refund.queryAttempts());
                }
            } catch (RuntimeException ignored) {
                scheduleRefundQuery(refund.id(), refund.queryAttempts());
            }
        }
    }

    private void verifyRefundResult(PendingRefund expected, WechatPayGateway.RefundData actual) {
        if (!expected.outRefundNo().equals(actual.outRefundNo())
            || !expected.outTradeNo().equals(actual.outTradeNo())
            || actual.amountFen() == null || actual.amountFen() != expected.amountFen()
            || actual.totalFen() == null || actual.totalFen() != expected.paymentAmountFen()
            || !"CNY".equals(actual.currency())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Refund result does not match the local order");
        }
    }

    private void scheduleRefundQuery(long refundId, int previousAttempts) {
        long delayMinutes = Math.min(60, 1L << Math.min(previousAttempts, 6));
        jdbc.sql("""
                UPDATE payment_refunds SET query_attempts=query_attempts+1,
                  next_query_at=DATE_ADD(CURRENT_TIMESTAMP(3), INTERVAL :delay MINUTE)
                WHERE id=:id AND status='PROCESSING'
                """).param("delay", delayMinutes).param("id", refundId).update();
    }

    private String normalizeRefundStatus(String status) {
        return switch (status) {
            case "SUCCESS", "CLOSED", "ABNORMAL", "PROCESSING" -> status;
            default -> "FAILED";
        };
    }

    private record ReservationForPayment(long id, String status, int quantity, Instant expiresAt,
                                         String itemName, int unitPriceFen, String openId) {}
    private record PaymentSeed(long id, String outTradeNo, int amountFen, String status, Instant expiresAt,
                               String openId, String itemName) {}
    private record PaymentCallbackRow(long id, long reservationId, long residentId, int amountFen,
                                      String currency, String status, String openId) {}
    private record RefundPaymentRow(long id, String outTradeNo, long residentId, long reservationId,
                                    int amountFen, String status, String reservationStatus) {}
    private record RefundSeed(long id, long paymentId, String outTradeNo, String outRefundNo,
                              int amountFen, String reason) {}
    private record RefundFinalizeRow(long id, int amountFen, long paymentId, long reservationId,
                                     long residentId, long inventoryItemId, int quantity,
                                     String reservationStatus) {}
    private record ExpiredPayment(long id, String outTradeNo) {}
    private record PendingRefund(long id, String outRefundNo, int amountFen, int queryAttempts,
                                 String outTradeNo, int paymentAmountFen) {}

    public record PaymentStart(long paymentId, String outTradeNo, int amountFen, String status,
                               WechatPayGateway.PrepayData requestPayment) {}
    public record PaymentView(long paymentId, String outTradeNo, long reservationId, String itemName,
                              int amountFen, String currency, String status, String transactionId,
                              Instant expiresAt, Instant paidAt, Instant createdAt) {}
    public record RefundView(long refundId, String outRefundNo, int amountFen, String status) {}
}
