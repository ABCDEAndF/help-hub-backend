package org.isolatedareas.helphub.impact;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class StockoutImpactListener {
    private final JdbcClient jdbc;

    public StockoutImpactListener(JdbcClient jdbc) { this.jdbc = jdbc; }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_ROLLBACK, fallbackExecution = true)
    public void record(StockoutAttempt event) {
        jdbc.sql("""
                INSERT INTO impact_events
                  (event_type, resident_id, request_id, value_number, metadata)
                VALUES ('STOCKOUT_RECORDED', :residentId, :requestId, :missing,
                  JSON_OBJECT('inventoryItemId', :itemId, 'requestedQuantity', :requested,
                    'availableQuantity', :available))
                """).param("residentId", event.residentId()).param("requestId", event.requestId())
            .param("missing", Math.max(0, event.requestedQuantity() - event.availableQuantity()))
            .param("itemId", event.inventoryItemId()).param("requested", event.requestedQuantity())
            .param("available", event.availableQuantity()).update();
    }
}
