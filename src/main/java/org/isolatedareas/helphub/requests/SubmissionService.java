package org.isolatedareas.helphub.requests;

import java.util.concurrent.ThreadLocalRandom;
import org.isolatedareas.helphub.api.IdempotencyService;
import org.isolatedareas.helphub.automation.AutoDecisionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A resident's submission — idempotency record, request and automatic decision — as one
 * transaction. Residents submitting at the same moment can make MySQL pick a deadlock victim or
 * lose an optimistic stock update; the whole transaction then rolls back and is simply retried.
 */
@Service
public class SubmissionService {
    private static final Logger log = LoggerFactory.getLogger(SubmissionService.class);
    static final int ATTEMPTS = 3;

    private final IdempotencyService idempotency;
    private final RequestService requests;
    private final AutoDecisionService decisions;
    private final TransactionTemplate transactions;
    private final JdbcClient jdbc;

    public SubmissionService(IdempotencyService idempotency, RequestService requests, AutoDecisionService decisions,
                             TransactionTemplate transactions, JdbcClient jdbc) {
        this.jdbc = jdbc;
        this.idempotency = idempotency;
        this.requests = requests;
        this.decisions = decisions;
        this.transactions = transactions;
    }

    public SupplyRequestView submit(long residentId, String idempotencyKey, CreateSupplyRequest input) {
        for (int attempt = 1; ; attempt++) {
            try {
                return transactions.execute(status -> {
                    // Locks are always taken in the same order: the resident's own row first (so one
                    // resident's submissions queue up), then the item's stock rows, then the rest.
                    // The idempotency record and the request both hold foreign-key locks on the
                    // resident row, so taking it any later lets one resident deadlock with themselves.
                    jdbc.sql("SELECT id FROM users WHERE id=:id FOR UPDATE").param("id", residentId).query(Long.class).optional();
                    return idempotency.execute(idempotencyKey, residentId,
                        "CREATE_SUPPLY_REQUEST", input, SupplyRequestView.class,
                        () -> {
                            if (input.inventoryItemId() != null) decisions.lockStock(input.inventoryItemId());
                            return decisions.decide(requests.create(residentId, input).id());
                        });
                });
            } catch (ConcurrencyFailureException conflict) {
                if (attempt >= ATTEMPTS) throw conflict;
                log.info("Submission by resident {} hit a concurrent update (attempt {}), retrying: {}",
                    residentId, attempt, conflict.getMessage());
                try {
                    Thread.sleep(ThreadLocalRandom.current().nextLong(20, 120) * attempt);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw conflict;
                }
            }
        }
    }
}
