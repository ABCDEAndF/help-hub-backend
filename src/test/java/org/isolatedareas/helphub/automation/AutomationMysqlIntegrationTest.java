package org.isolatedareas.helphub.automation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.function.Supplier;
import org.flywaydb.core.Flyway;
import org.isolatedareas.helphub.audit.AuditService;
import org.isolatedareas.helphub.domain.FulfillmentMethod;
import org.isolatedareas.helphub.domain.RequestStatus;
import org.isolatedareas.helphub.domain.Urgency;
import org.isolatedareas.helphub.events.OutboxService;
import org.isolatedareas.helphub.inventory.InventoryRepository;
import org.isolatedareas.helphub.inventory.ReservationCollected;
import org.isolatedareas.helphub.inventory.ReservationExpired;
import org.isolatedareas.helphub.inventory.ReservationService;
import org.isolatedareas.helphub.requests.CreateSupplyRequest;
import org.isolatedareas.helphub.requests.RequestService;
import org.isolatedareas.helphub.requests.SupplyRequestRepository;
import org.isolatedareas.helphub.requests.SupplyRequestView;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runs the automatic approval, reservation, cart dispatch and simulated delivery against a
 * real MySQL database migrated with the production migrations (set REPRO_MYSQL_URL,
 * REPRO_MYSQL_USER and REPRO_MYSQL_PASSWORD).
 */
@EnabledIfEnvironmentVariable(named = "REPRO_MYSQL_URL", matches = ".+")
class AutomationMysqlIntegrationTest {
    // Close to the industrial-park service point, far from Xiayang.
    private static final BigDecimal NEAR_INDUSTRIAL_LAT = new BigDecimal("31.1790");
    private static final BigDecimal NEAR_INDUSTRIAL_LON = new BigDecimal("121.0950");

    @Test
    void approvesReservesDispatchesAndDeliversAutomatically() {
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
        // Delivers the events Spring would, so handover and expiry close requests as in production.
        ApplicationEventPublisher publisher = event -> {
            if (event instanceof ReservationCollected collected) lifecycle.onCollected(collected);
            if (event instanceof ReservationExpired expired) lifecycle.onExpired(expired);
        };
        var reservations = new ReservationService(jdbc, new InventoryRepository(jdbc), requests,
            mock(StringRedisTemplate.class, RETURNS_DEEP_STUBS), outbox, audit, publisher,
            "integration-test-pickup-code-secret-0123456789");
        var decisions = new AutoDecisionService(jdbc, requests, requestService, reservations, system);
        var trips = new CartTripService(jdbc, requestService, requests, reservations, system);
        var tx = new TransactionTemplate(new DataSourceTransactionManager(ds));

        assertThat(jdbc.sql("""
                SELECT CONCAT(table_name, '.', column_name) FROM information_schema.columns
                WHERE table_schema = DATABASE() AND LOWER(extra) LIKE '%on update%' AND column_name <> 'updated_at'
                """).query(String.class).list()).isEmpty();
        assertThat(system.id()).isPositive();

        // Emoji are 4-byte UTF-8; the CloudRun template database is 3-byte utf8 until V16.
        jdbc.sql("INSERT INTO users (wechat_open_id, display_name) VALUES ('it-resident', '测试居民😀')").update();
        long resident = jdbc.sql("SELECT id FROM users WHERE wechat_open_id='it-resident'").query(Long.class).single();
        long xiayangRice = itemId(jdbc, "QP-XY-RICE-5KG");
        long industrialRice = itemId(jdbc, "QP-GY-RICE-5KG");
        long torch = itemId(jdbc, "QP-XY-LIGHT-01");
        Supplier<Integer> industrialRiceReserved = () -> jdbc.sql(
            "SELECT reserved_quantity FROM inventory_items WHERE id=:id").param("id", industrialRice).query(Integer.class).single();

        // Pickup: the Xiayang rice was chosen, but the resident lives next to the industrial park,
        // so the same rice is reserved there and the request is scheduled at that point.
        SupplyRequestView pickup = tx.execute(s -> decisions.decide(requestService.create(resident,
            request(xiayangRice, 2, FulfillmentMethod.PICKUP)).id()));
        assertThat(pickup.status()).isEqualTo(RequestStatus.SCHEDULED);
        assertThat(pickup.residentNumber()).isEqualTo(1);
        assertThat(pickup.category()).isEqualTo("FOOD");
        assertThat(pickup.assignedServicePointName()).isEqualTo("邻需通·青浦工业园区公益服务点");
        assertThat(pickup.decisionNote()).startsWith("已自动批准").contains("09:00–17:00");
        assertThat(industrialRiceReserved.get()).isEqualTo(2);

        // Not enough stock anywhere: rejected with the reason.
        SupplyRequestView tooMany = tx.execute(s -> decisions.decide(requestService.create(resident,
            request(torch, 100, FulfillmentMethod.PICKUP)).id()));
        assertThat(tooMany.status()).isEqualTo(RequestStatus.REJECTED);
        assertThat(tooMany.residentNumber()).isEqualTo(2);
        assertThat(tooMany.decisionNote()).contains("库存不足", "25套");

        // Not a stocked item: left for an operator.
        SupplyRequestView other = tx.execute(s -> decisions.decide(requestService.create(resident,
            request(null, 1, FulfillmentMethod.DELIVERY)).id()));
        assertThat(other.status()).isEqualTo(RequestStatus.SUBMITTED);
        assertThat(other.decisionNote()).isEqualTo(AutoDecisionService.MANUAL_NOTE);
        assertThat(other.itemDescription()).isEqualTo("集成测试🍚");
        jdbc.sql("INSERT INTO assistant_conversations (id, user_id) VALUES ('00000000-0000-0000-0000-000000000001', :u)")
            .param("u", resident).update();
        jdbc.sql("""
                INSERT INTO assistant_messages (conversation_id, role, content)
                VALUES ('00000000-0000-0000-0000-000000000001', 'ASSISTANT', '📍 夏阳服务点 08:30–16:30')
                """).update();

        // Delivery: approved with stock held, then the nearest cart collects and delivers it.
        SupplyRequestView delivery = tx.execute(s -> decisions.decide(requestService.create(resident,
            request(industrialRice, 1, FulfillmentMethod.DELIVERY)).id()));
        assertThat(delivery.status()).isEqualTo(RequestStatus.APPROVED);
        assertThat(trips.dispatchNeeded()).isTrue();
        CartTripService.PlanOutcome outcome = tx.execute(s -> trips.planTrips(null));
        assertThat(outcome.assignedRequestIds()).containsExactly(delivery.id());
        SupplyRequestView scheduled = requestService.get(delivery.id());
        assertThat(scheduled.status()).isEqualTo(RequestStatus.SCHEDULED);
        assertThat(scheduled.assignedCartName()).isEqualTo("青浦公益补给车二号");
        assertThat(scheduled.decisionNote()).contains("预计", "送达");
        assertThat(trips.dispatchNeeded()).isFalse();

        var tracking = trips.tracking(delivery.id(), resident, Instant.now()).orElseThrow();
        assertThat(tracking.cartName()).isEqualTo("青浦公益补给车二号");
        assertThat(tracking.delivered()).isFalse();
        assertThat(tracking.path()).hasSizeGreaterThanOrEqualTo(2);
        assertThat(trips.tracking(delivery.id(), resident + 999, Instant.now())).isEmpty();
        assertThat(trips.motions(Instant.now())).containsKey(scheduled.assignedCartId());
        assertThat(trips.adminTrips(Instant.now())).singleElement()
            .satisfies(trip -> assertThat(trip.stops()).extracting(CartTripService.AdminStop::type)
                .containsExactly("PICKUP", "DROPOFF", "RETURN"));

        long tripId = jdbc.sql("SELECT id FROM cart_trips WHERE status='ACTIVE'").query(Long.class).single();
        tx.executeWithoutResult(s -> trips.advanceTrip(tripId, Instant.now().plusSeconds(3 * 3600)));
        SupplyRequestView fulfilled = requestService.get(delivery.id());
        assertThat(fulfilled.status()).isEqualTo(RequestStatus.FULFILLED);
        assertThat(fulfilled.decisionNote()).contains("已", "送达");
        assertThat(jdbc.sql("SELECT status FROM cart_trips WHERE id=:id").param("id", tripId).query(String.class).single())
            .isEqualTo("COMPLETED");
        assertThat(jdbc.sql("SELECT status FROM mobile_carts WHERE id=:id").param("id", scheduled.assignedCartId())
            .query(String.class).single()).isEqualTo("AVAILABLE");
        assertThat(jdbc.sql("SELECT available_quantity FROM inventory_items WHERE id=:id").param("id", industrialRice)
            .query(Integer.class).single()).isEqualTo(99);
        assertThat(industrialRiceReserved.get()).isEqualTo(2);
        assertThat(trips.tracking(delivery.id(), resident, Instant.now()).orElseThrow().delivered()).isTrue();

        // Cancelling the scheduled pickup releases the two bags it was holding.
        tx.execute(s -> requestService.transition(pickup.id(), system.id(),
            new RequestService.TransitionRequest(RequestStatus.CANCELLED, null, null)));
        assertThat(industrialRiceReserved.get()).isZero();
        assertThat(jdbc.sql("SELECT status FROM reservations WHERE request_id=:id").param("id", pickup.id())
            .query(String.class).single()).isEqualTo("CANCELLED");

        // Staff verifying the pickup code completes the request; nobody has to mark it done.
        SupplyRequestView torchPickup = tx.execute(s -> decisions.decide(requestService.create(resident,
            request(torch, 1, FulfillmentMethod.PICKUP)).id()));
        var torchReservation = reservations.list(resident).stream()
            .filter(item -> item.requestId() == torchPickup.id()).findFirst().orElseThrow();
        assertThat(torchReservation.requestNumber()).isEqualTo(torchPickup.residentNumber());
        var handover = tx.execute(s -> reservations.collectByCode(system.id(), torchReservation.pickupCode()));
        assertThat(handover.itemName()).isEqualTo("应急手电筒与电池包");
        assertThat(handover.quantity()).isEqualTo(1);
        assertThat(handover.requestNumber()).isEqualTo(torchPickup.residentNumber());
        SupplyRequestView collected = requestService.get(torchPickup.id());
        assertThat(collected.status()).isEqualTo(RequestStatus.FULFILLED);
        assertThat(collected.decisionNote()).contains("领取", "已完成");

        // A pickup code left unused past its hold cancels the request and frees the stock.
        SupplyRequestView forgotten = tx.execute(s -> decisions.decide(requestService.create(resident,
            request(industrialRice, 1, FulfillmentMethod.PICKUP)).id()));
        assertThat(industrialRiceReserved.get()).isEqualTo(1);
        jdbc.sql("UPDATE reservations SET expires_at = CURRENT_TIMESTAMP(3) - INTERVAL 1 MINUTE WHERE request_id=:id")
            .param("id", forgotten.id()).update();
        tx.executeWithoutResult(s -> reservations.expireHolds());
        SupplyRequestView lapsed = requestService.get(forgotten.id());
        assertThat(lapsed.status()).isEqualTo(RequestStatus.CANCELLED);
        assertThat(lapsed.decisionNote()).contains("48 小时");
        assertThat(industrialRiceReserved.get()).isZero();

        // A courier may close a delivery before the cart's simulated arrival; the cart then skips the stop.
        SupplyRequestView courier = tx.execute(s -> decisions.decide(requestService.create(resident,
            request(industrialRice, 1, FulfillmentMethod.DELIVERY)).id()));
        tx.execute(s -> trips.planTrips(null));
        assertThat(requestService.get(courier.id()).status()).isEqualTo(RequestStatus.SCHEDULED);
        tx.executeWithoutResult(s -> trips.markDelivered(courier.id(), system.id()));
        assertThat(requestService.get(courier.id()).status()).isEqualTo(RequestStatus.FULFILLED);
        assertThat(trips.tracking(courier.id(), resident, Instant.now()).orElseThrow().delivered()).isTrue();
        long courierTrip = jdbc.sql("SELECT id FROM cart_trips WHERE status='ACTIVE'").query(Long.class).single();
        tx.executeWithoutResult(s -> trips.advanceTrip(courierTrip, Instant.now().plusSeconds(3 * 3600)));
        assertThat(requestService.get(courier.id()).decisionNote()).contains("工作人员", "标记送达");
        assertThat(industrialRiceReserved.get()).isZero();

        // Every resident's own numbering starts at 1.
        jdbc.sql("INSERT INTO users (wechat_open_id, display_name) VALUES ('it-neighbour', '邻居')").update();
        long neighbour = jdbc.sql("SELECT id FROM users WHERE wechat_open_id='it-neighbour'").query(Long.class).single();
        SupplyRequestView first = tx.execute(s -> requestService.create(neighbour, request(null, 1, FulfillmentMethod.PICKUP)));
        assertThat(first.residentNumber()).isEqualTo(1);
        assertThat(first.id()).isGreaterThan(1);

        // Staff accounts: stored as BCrypt hashes, resettable, never matching residents.
        var users = new org.isolatedareas.helphub.auth.UserRepository(jdbc);
        var courierAccount = users.upsertStaff("kd001", "快递员1", "$2a$10$abcdefghijklmnopqrstuuJ4O3n2m1l0k9j8i7h6g5f4e3d2c1b0a");
        assertThat(courierAccount.role().name()).isEqualTo("OPERATOR");
        assertThat(users.staffPasswordHash("kd001")).hasValueSatisfying(hash -> assertThat(hash).startsWith("$2a$"));
        assertThat(users.upsertStaff("kd001", "快递员一号", "$2a$10$reset").displayName()).isEqualTo("快递员一号");
        assertThat(users.staffPasswordHash("kd001")).contains("$2a$10$reset");
        assertThat(users.staff()).extracting(org.isolatedareas.helphub.auth.UserAccount::phone).contains("kd001");
        assertThat(users.staffPasswordHash("it-resident")).isEmpty();
    }

    private static long itemId(JdbcClient jdbc, String sku) {
        return jdbc.sql("SELECT id FROM inventory_items WHERE sku=:sku").param("sku", sku).query(Long.class).single();
    }

    private static CreateSupplyRequest request(Long itemId, int quantity, FulfillmentMethod method) {
        return new CreateSupplyRequest("OTHER", "集成测试🍚", quantity, Urgency.NORMAL, NEAR_INDUSTRIAL_LAT,
            NEAR_INDUSTRIAL_LON, null, null, null, null, method, itemId);
    }
}
