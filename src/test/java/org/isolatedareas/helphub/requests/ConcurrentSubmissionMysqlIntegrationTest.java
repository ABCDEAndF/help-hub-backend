package org.isolatedareas.helphub.requests;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.isolatedareas.helphub.api.IdempotencyService;
import org.isolatedareas.helphub.audit.AuditService;
import org.isolatedareas.helphub.automation.ApprovalService;
import org.isolatedareas.helphub.automation.RequestLifecycleListener;
import org.isolatedareas.helphub.automation.SystemActor;
import org.isolatedareas.helphub.domain.FulfillmentMethod;
import org.isolatedareas.helphub.domain.RequestStatus;
import org.isolatedareas.helphub.domain.Urgency;
import org.isolatedareas.helphub.events.OutboxService;
import org.isolatedareas.helphub.inventory.InventoryRepository;
import org.isolatedareas.helphub.inventory.ReservationCollected;
import org.isolatedareas.helphub.inventory.ReservationExpired;
import org.isolatedareas.helphub.inventory.ReservationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Many first-time residents submitting at the same moment, through the same idempotent,
 * transactional path as the API, must all get their request, and staff approving them all at
 * once must reserve stock for every one without deadlocking (set REPRO_MYSQL_URL etc.).
 */
@EnabledIfEnvironmentVariable(named = "REPRO_MYSQL_URL", matches = ".+")
class ConcurrentSubmissionMysqlIntegrationTest {
    private static final int RESIDENTS = 20;

    @Test
    void simultaneousFirstSubmissionsAllSucceed() throws Exception {
        var ds = new DriverManagerDataSource(System.getenv("REPRO_MYSQL_URL"),
            System.getenv("REPRO_MYSQL_USER"), System.getenv("REPRO_MYSQL_PASSWORD"));
        Flyway.configure().dataSource(ds).locations("classpath:db/migration").cleanDisabled(false).load().clean();
        Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();
        var jdbc = JdbcClient.create(ds);
        var json = new ObjectMapper().findAndRegisterModules();
        var audit = new AuditService(jdbc, json);
        var outbox = new OutboxService(jdbc, json);
        var requests = new SupplyRequestRepository(jdbc);
        var requestService = new RequestService(requests, outbox, audit, jdbc);
        var system = new SystemActor(jdbc);
        var lifecycle = new RequestLifecycleListener(requestService, requests, system, jdbc);
        ApplicationEventPublisher publisher = event -> {
            if (event instanceof ReservationCollected collected) lifecycle.onCollected(collected);
            if (event instanceof ReservationExpired expired) lifecycle.onExpired(expired);
        };
        var reservations = new ReservationService(jdbc, new InventoryRepository(jdbc), requests,
            mock(StringRedisTemplate.class, RETURNS_DEEP_STUBS), outbox, audit, publisher,
            "integration-test-pickup-code-secret-0123456789");
        var approvals = new ApprovalService(jdbc, requests, requestService, reservations);
        var idempotency = new IdempotencyService(jdbc, json);
        var tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        var submissions = new SubmissionService(idempotency, requestService, approvals, tx, jdbc);

        List<Long> residents = new ArrayList<>();
        for (int index = 0; index < RESIDENTS; index++) {
            jdbc.sql("INSERT INTO users (wechat_open_id, display_name) VALUES (:open, :name)")
                .param("open", "concurrent-" + index).param("name", "居民" + index).update();
            residents.add(jdbc.sql("SELECT id FROM users WHERE wechat_open_id=:open").param("open", "concurrent-" + index)
                .query(Long.class).single());
        }
        List<Long> items = jdbc.sql("SELECT id FROM inventory_items WHERE sku LIKE 'QP-%' AND available_quantity >= 20 ORDER BY id LIMIT 5")
            .query(Long.class).list();

        ExecutorService pool = Executors.newFixedThreadPool(RESIDENTS);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<SupplyRequestView>> futures = new ArrayList<>();
        List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());
        for (int index = 0; index < RESIDENTS; index++) {
            long resident = residents.get(index);
            long item = items.get(index % items.size());
            CreateSupplyRequest input = new CreateSupplyRequest("FOOD", "同时下单", 1, Urgency.NORMAL,
                new BigDecimal("31.1790"), new BigDecimal("121.0950"), "青浦区清河湾路", null, null, null,
                index % 2 == 0 ? FulfillmentMethod.DELIVERY : FulfillmentMethod.PICKUP, item);
            futures.add(pool.submit(() -> {
                start.await();
                try {
                    return submissions.submit(resident, "concurrent-" + UUID.randomUUID(), input);
                } catch (RuntimeException error) {
                    failures.add(error);
                    throw error;
                }
            }));
        }
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).isTrue();
        assertThat(failures).as("failures: %s", failures).isEmpty();
        List<Long> submitted = new ArrayList<>();
        for (Future<SupplyRequestView> future : futures) {
            SupplyRequestView view = future.get();
            assertThat(view.residentNumber()).isEqualTo(1);
            // Nothing is approved or reserved until staff say so.
            assertThat(view.status()).isEqualTo(RequestStatus.SUBMITTED);
            submitted.add(view.id());
        }
        assertThat(jdbc.sql("SELECT COUNT(*) FROM supply_requests").query(Integer.class).single()).isEqualTo(RESIDENTS);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM reservations").query(Integer.class).single()).isZero();

        // Staff approving every request at the same moment: each one gets its stock.
        ExecutorService staff = Executors.newFixedThreadPool(RESIDENTS);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<SupplyRequestView>> approved = new ArrayList<>();
        for (long id : submitted) {
            approved.add(staff.submit(() -> {
                go.await();
                return tx.execute(s -> approvals.approve(id, system.id()));
            }));
        }
        go.countDown();
        staff.shutdown();
        assertThat(staff.awaitTermination(60, TimeUnit.SECONDS)).isTrue();
        for (Future<SupplyRequestView> future : approved) {
            assertThat(future.get().status()).isIn(RequestStatus.APPROVED, RequestStatus.SCHEDULED);
        }
        assertThat(jdbc.sql("SELECT COUNT(*) FROM reservations WHERE status='HELD'").query(Integer.class).single())
            .isEqualTo(RESIDENTS);

        // The same resident submitting twice at once still gets numbers 1 and 2, never a clash.
        long twice = residents.get(0);
        ExecutorService pair = Executors.newFixedThreadPool(2);
        List<Future<SupplyRequestView>> both = new ArrayList<>();
        for (int index = 0; index < 2; index++) {
            CreateSupplyRequest input = new CreateSupplyRequest("FOOD", "再次下单", 1, Urgency.NORMAL,
                new BigDecimal("31.1790"), new BigDecimal("121.0950"), null, null, null, null,
                FulfillmentMethod.PICKUP, items.get(0));
            both.add(pair.submit(() -> submissions.submit(twice, "again-" + UUID.randomUUID(), input)));
        }
        pair.shutdown();
        assertThat(pair.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        List<Integer> numbers = new ArrayList<>();
        for (Future<SupplyRequestView> future : both) numbers.add(future.get().residentNumber());
        assertThat(numbers).containsExactlyInAnyOrder(2, 3);
    }
}
