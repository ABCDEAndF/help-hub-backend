package org.isolatedareas.helphub.requests;

import java.util.concurrent.ThreadLocalRandom;
import org.isolatedareas.helphub.api.IdempotencyService;
import org.isolatedareas.helphub.automation.ApprovalService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A resident's submission — idempotency record and request — as one transaction. The request
 * then waits for a staff member to approve it. Residents submitting at the same moment can make
 * MySQL pick a deadlock victim; the whole transaction then rolls back and is simply retried.
 */
@Service
public class SubmissionService {
    private static final Logger log = LoggerFactory.getLogger(SubmissionService.class);
    static final int ATTEMPTS = 3;

    private final IdempotencyService idempotency;
    private final RequestService requests;
    private final ApprovalService approvals;
    private final TransactionTemplate transactions;
    private final JdbcClient jdbc;

    public SubmissionService(IdempotencyService idempotency, RequestService requests, ApprovalService approvals,
                             TransactionTemplate transactions, JdbcClient jdbc) {
        this.jdbc = jdbc;
        this.idempotency = idempotency;
        this.requests = requests;
        this.approvals = approvals;
        this.transactions = transactions;
    }

    public SupplyRequestView submit(long residentId, String idempotencyKey, CreateSupplyRequest input) {
        for (int attempt = 1; ; attempt++) {
            try {
                return transactions.execute(status -> {
                    // The resident's own row first, so one resident's submissions queue up. The
                    // idempotency record and the request both hold foreign-key locks on the resident
                    // row, so taking it any later lets one resident deadlock with themselves.
                    jdbc.sql("SELECT id FROM users WHERE id=:id FOR UPDATE").param("id", residentId).query(Long.class).optional();
                    return idempotency.execute(idempotencyKey, residentId,
                        "CREATE_SUPPLY_REQUEST", input, SupplyRequestView.class,
                        () -> approvals.submitted(requests.create(residentId, input).id()));
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
