package org.isolatedareas.helphub.simulation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validation;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.isolatedareas.helphub.api.IdempotencyService;
import org.isolatedareas.helphub.assistant.AssistantService;
import org.isolatedareas.helphub.assistant.AssistantToolExecutor;
import org.isolatedareas.helphub.assistant.ConfirmationService;
import org.isolatedareas.helphub.assistant.OpenAiCompatibleClient;
import org.isolatedareas.helphub.audit.AuditService;
import org.isolatedareas.helphub.automation.AutoDecisionService;
import org.isolatedareas.helphub.automation.RequestLifecycleListener;
import org.isolatedareas.helphub.automation.SystemActor;
import org.isolatedareas.helphub.events.OutboxService;
import org.isolatedareas.helphub.geo.GeoMath;
import org.isolatedareas.helphub.geo.ServiceArea;
import org.isolatedareas.helphub.inventory.InventoryRepository;
import org.isolatedareas.helphub.inventory.ReservationCollected;
import org.isolatedareas.helphub.inventory.ReservationExpired;
import org.isolatedareas.helphub.inventory.ReservationService;
import org.isolatedareas.helphub.requests.RequestService;
import org.isolatedareas.helphub.requests.SubmissionService;
import org.isolatedareas.helphub.requests.SupplyRequestRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;

/**
 * Plays a day and a half of resident activity against a real MySQL database migrated with the
 * production migrations (set REPRO_MYSQL_URL, REPRO_MYSQL_USER and REPRO_MYSQL_PASSWORD).
 */
@EnabledIfEnvironmentVariable(named = "REPRO_MYSQL_URL", matches = ".+")
class ResidentActivityMysqlIntegrationTest {
    @Test
    void residentsOrderAskAndGetTheirRequestsDecidedLikeAnyOther() {
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
        var redis = mock(StringRedisTemplate.class, RETURNS_DEEP_STUBS);
        var inventory = new InventoryRepository(jdbc);
        var reservations = new ReservationService(jdbc, inventory, requests, redis, outbox, audit, publisher,
            "integration-test-pickup-code-secret-0123456789");
        var decisions = new AutoDecisionService(jdbc, requests, requestService, reservations, system);
        var tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        var submissions = new SubmissionService(new IdempotencyService(jdbc, json), requestService, decisions, tx, jdbc);
        var tools = new AssistantToolExecutor(inventory, requests, reservations, jdbc,
            new ConfirmationService(redis, json), Validation.buildDefaultValidatorFactory().getValidator(), submissions);
        var model = new OpenAiCompatibleClient("https://example.invalid", "", "none", RestClient.builder(), json);
        var assistant = new AssistantService(model, tools, jdbc, json, 4);
        var area = new ServiceArea(jdbc);
        var simulator = new ResidentActivitySimulator(jdbc, submissions, assistant, reservations, inventory, area,
            system, tx, true);

        Instant now = Instant.now();
        Timestamp signedIn = Timestamp.from(now.minus(Duration.ofDays(3)));
        for (int n = 1; n <= 40; n++) {
            jdbc.sql("INSERT INTO users (wechat_open_id, display_name, created_at) VALUES (:open, :name, :at)")
                .param("open", "sim-it-" + n).param("name", "居民" + n).param("at", signedIn).update();
        }
        // Signed in long ago: their 90 days are over.
        jdbc.sql("INSERT INTO users (wechat_open_id, display_name, created_at) VALUES ('sim-it-old', '老居民', :at)")
            .param("at", Timestamp.from(now.minus(Duration.ofDays(120)))).update();
        long oldResident = jdbc.sql("SELECT id FROM users WHERE wechat_open_id='sim-it-old'").query(Long.class).single();
        // Signed in by scripts/production-smoke.ps1, not by a person.
        jdbc.sql("INSERT INTO users (wechat_open_id, display_name, created_at) VALUES ('production-smoke-it', '冒烟', :at)")
            .param("at", signedIn).update();
        long smokeResident = jdbc.sql("SELECT id FROM users WHERE wechat_open_id='production-smoke-it'").query(Long.class).single();

        simulator.assignMissingDefaultLocations();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM users WHERE role='RESIDENT' AND home_latitude IS NULL")
            .query(Integer.class).single()).isZero();

        int completed = simulator.play(now.minus(Duration.ofHours(30)), now);
        assertThat(completed).isPositive();

        int orders = jdbc.sql("SELECT COUNT(*) FROM supply_requests").query(Integer.class).single();
        assertThat(orders).isPositive();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM supply_requests WHERE resident_id=:id").param("id", oldResident)
            .query(Integer.class).single()).isZero();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM supply_requests WHERE resident_id=:id").param("id", smokeResident)
            .query(Integer.class).single()).isZero();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM assistant_conversations WHERE user_id=:id").param("id", smokeResident)
            .query(Integer.class).single()).isZero();
        // Stocked items were decided automatically, exactly as for a request from the mini program.
        assertThat(jdbc.sql("""
                SELECT COUNT(*) FROM supply_requests
                WHERE inventory_item_id IS NOT NULL AND status IN ('SUBMITTED','UNDER_REVIEW')
                """).query(Integer.class).single()).isZero();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM reservations").query(Integer.class).single()).isPositive();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM audit_log WHERE action='REQUEST_CREATED'").query(Integer.class).single())
            .isEqualTo(orders);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM outbox_events WHERE event_type='SupplyRequestCreated'")
            .query(Integer.class).single()).isEqualTo(orders);
        // Every request is inside the service area.
        List<double[]> points = jdbc.sql("SELECT latitude, longitude FROM service_points WHERE status='ACTIVE'")
            .query((rs, n) -> new double[] {rs.getDouble(1), rs.getDouble(2)}).list();
        jdbc.sql("SELECT latitude, longitude FROM supply_requests")
            .query((rs, n) -> new double[] {rs.getDouble(1), rs.getDouble(2)}).list()
            .forEach(request -> assertThat(points.stream().mapToDouble(point ->
                GeoMath.distanceMeters(request[0], request[1], point[0], point[1])).min().orElseThrow())
                .isLessThan(ServiceArea.RADIUS_METERS + 800));

        assertThat(jdbc.sql("SELECT COUNT(*) FROM assistant_messages WHERE role='USER'").query(Integer.class).single())
            .isPositive();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM assistant_messages WHERE role='ASSISTANT'").query(Integer.class).single())
            .isEqualTo(jdbc.sql("SELECT COUNT(*) FROM assistant_messages WHERE role='USER'").query(Integer.class).single());

        // Playing the same span again does not repeat anything already done by then.
        int before = jdbc.sql("SELECT COUNT(*) FROM supply_requests").query(Integer.class).single();
        simulator.play(now, now);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM supply_requests").query(Integer.class).single()).isEqualTo(before);
    }
}
