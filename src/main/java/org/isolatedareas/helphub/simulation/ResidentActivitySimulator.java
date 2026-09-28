package org.isolatedareas.helphub.simulation;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SplittableRandom;
import java.util.UUID;
import org.isolatedareas.helphub.assistant.AssistantModels;
import org.isolatedareas.helphub.assistant.AssistantService;
import org.isolatedareas.helphub.automation.SystemActor;
import org.isolatedareas.helphub.domain.FulfillmentMethod;
import org.isolatedareas.helphub.domain.Urgency;
import org.isolatedareas.helphub.geo.ServiceArea;
import org.isolatedareas.helphub.inventory.InventoryItemView;
import org.isolatedareas.helphub.inventory.InventoryRepository;
import org.isolatedareas.helphub.inventory.ReservationService;
import org.isolatedareas.helphub.requests.CreateSupplyRequest;
import org.isolatedareas.helphub.requests.SubmissionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Acts out every signed-in resident's day, for 90 days from their first sign-in: they submit
 * requests, ask the assistant, collect pickups and leave feedback through the same services the
 * mini program calls, so approval, stock, dispatch, couriers' routes and notifications all
 * follow exactly as for any request. Only today's activities are played, as their time comes.
 */
@Component
public class ResidentActivitySimulator {
    private static final Logger log = LoggerFactory.getLogger(ResidentActivitySimulator.class);
    private static final Duration LEASE = Duration.ofMinutes(3);
    /** After downtime, activities older than this are skipped instead of all firing at once. */
    static final Duration MAX_CATCH_UP = Duration.ofMinutes(15);
    /** Identities scripts/production-smoke.ps1 signs in with; they are not people, so they are not acted out. */
    static final String SMOKE_TEST_OPEN_IDS = "production-smoke-%";

    private final JdbcClient jdbc;
    private final SubmissionService submissions;
    private final AssistantService assistant;
    private final ReservationService reservations;
    private final InventoryRepository inventory;
    private final ServiceArea area;
    private final SystemActor system;
    private final TransactionTemplate transactions;
    private final boolean enabled;
    private final String instanceId = UUID.randomUUID().toString();
    private final Map<String, List<DailyActivityPlanner.Activity>> plans = new HashMap<>();
    private LocalDate plannedDay;

    public ResidentActivitySimulator(JdbcClient jdbc, SubmissionService submissions, AssistantService assistant,
                                     ReservationService reservations, InventoryRepository inventory, ServiceArea area,
                                     SystemActor system, TransactionTemplate transactions,
                                     @Value("${app.simulation.enabled:false}") boolean enabled) {
        this.jdbc = jdbc;
        this.submissions = submissions;
        this.assistant = assistant;
        this.reservations = reservations;
        this.inventory = inventory;
        this.area = area;
        this.system = system;
        this.transactions = transactions;
        this.enabled = enabled;
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 90_000)
    public void run() {
        if (!enabled) return;
        try {
            if (!acquireLease()) return;
            assignMissingDefaultLocations();
            Instant now = Instant.now();
            Instant from = processedUntil().filter(at -> at.isAfter(now.minus(MAX_CATCH_UP))).orElse(now.minus(Duration.ofMinutes(1)));
            play(from, now);
            saveProgress(now);
        } catch (RuntimeException failure) {
            log.warn("Resident activity run failed; retrying next minute", failure);
        }
    }

    /** Carries out, in time order, every activity due in (from, now]; returns how many completed. */
    int play(Instant from, Instant now) {
        int completed = 0;
        for (DailyActivityPlanner.Activity activity : due(from, now)) {
            try {
                perform(activity, now);
                completed++;
            } catch (RuntimeException failure) {
                log.info("Resident {} activity {} did not complete: {}", activity.userId(), activity.type(), failure.getMessage());
            }
            saveProgress(activity.at());
        }
        return completed;
    }

    /** One instance runs at a time; the lease expires by itself if that instance dies. */
    private boolean acquireLease() {
        return jdbc.sql("""
                UPDATE resident_activity_state
                SET lease_owner=:me, lease_until=:until
                WHERE id=1 AND (lease_until IS NULL OR lease_until < CURRENT_TIMESTAMP(3) OR lease_owner=:me)
                """).param("me", instanceId).param("until", Timestamp.from(Instant.now().plus(LEASE))).update() == 1;
    }

    private Optional<Instant> processedUntil() {
        return jdbc.sql("SELECT processed_until FROM resident_activity_state WHERE id=1")
            .query((rs, n) -> Optional.ofNullable(rs.getTimestamp("processed_until")).map(Timestamp::toInstant))
            .single();
    }

    private void saveProgress(Instant at) {
        jdbc.sql("""
                UPDATE resident_activity_state SET processed_until=:at, lease_until=:until
                WHERE id=1 AND lease_owner=:me
                """).param("at", Timestamp.from(at)).param("until", Timestamp.from(Instant.now().plus(LEASE)))
            .param("me", instanceId).update();
    }

    /** Every resident gets a random default location in the service area, as the profile page does. */
    void assignMissingDefaultLocations() {
        List<Long> missing = jdbc.sql("SELECT id FROM users WHERE role='RESIDENT' AND home_latitude IS NULL")
            .query(Long.class).list();
        for (long userId : missing) {
            double[] point = area.randomPoint();
            jdbc.sql("""
                    UPDATE users SET home_latitude=:lat, home_longitude=:lon
                    WHERE id=:id AND role='RESIDENT' AND home_latitude IS NULL
                    """).param("lat", point[0]).param("lon", point[1]).param("id", userId).update();
        }
    }

    /** Activities of every real resident in their 90 days whose time falls in (from, to], in time order. */
    List<DailyActivityPlanner.Activity> due(Instant from, Instant to) {
        LocalDate today = to.atZone(DailyActivityPlanner.ZONE).toLocalDate();
        if (!today.equals(plannedDay)) {
            plans.clear();
            plannedDay = today;
        }
        List<Resident> residents = jdbc.sql("""
                SELECT id, created_at FROM users
                WHERE role='RESIDENT' AND enabled=TRUE AND created_at > :since
                  AND (wechat_open_id IS NULL OR wechat_open_id NOT LIKE :smoke)
                """).param("smoke", SMOKE_TEST_OPEN_IDS).param("since", Timestamp.from(to.minus(Duration.ofDays(ResidentPersona.SIMULATED_DAYS + 1))))
            .query((rs, n) -> new Resident(rs.getLong("id"), rs.getTimestamp("created_at").toInstant())).list();
        List<DailyActivityPlanner.Activity> due = new ArrayList<>();
        LocalDate fromDay = from.atZone(DailyActivityPlanner.ZONE).toLocalDate();
        for (Resident resident : residents) {
            for (LocalDate day = fromDay; !day.isAfter(today); day = day.plusDays(1)) {
                LocalDate planDay = day;
                List<DailyActivityPlanner.Activity> plan = day.equals(today)
                    ? plans.computeIfAbsent(resident.id() + "@" + resident.activatedAt(),
                        key -> DailyActivityPlanner.plan(resident.id(), resident.activatedAt(), planDay))
                    : DailyActivityPlanner.plan(resident.id(), resident.activatedAt(), planDay);
                for (DailyActivityPlanner.Activity activity : plan) {
                    if (activity.at().isAfter(from) && !activity.at().isAfter(to)) due.add(activity);
                }
            }
        }
        due.sort(Comparator.comparing(DailyActivityPlanner.Activity::at)
            .thenComparingLong(DailyActivityPlanner.Activity::userId).thenComparingInt(DailyActivityPlanner.Activity::index));
        return due;
    }

    private void perform(DailyActivityPlanner.Activity activity, Instant now) {
        switch (activity.type()) {
            case ORDER -> order(activity, now);
            case CHAT -> chat(activity.userId(), activity.chat());
            case PICKUP_CHECK -> collectDuePickups(activity.userId(), now);
            case FEEDBACK_CHECK -> leaveFeedback(activity.userId(), now);
        }
    }

    // ---- orders ----

    private void order(DailyActivityPlanner.Activity activity, Instant now) {
        long userId = activity.userId();
        DailyActivityPlanner.OrderIntent intent = activity.order();
        ResidentPersona persona = ResidentPersona.of(userId);
        SplittableRandom random = new SplittableRandom(intent.choiceSeed());
        double[] place = place(userId, intent);
        Instant[] window = window(intent.booking(), now);
        String note = intent.noteIndex() >= 0 ? Pools.NOTES.get(intent.noteIndex()) : null;
        FulfillmentMethod method = intent.delivery() ? FulfillmentMethod.DELIVERY : FulfillmentMethod.PICKUP;

        if (intent.manual()) {
            String[] need = Pools.MANUAL_NEEDS.get(random.nextInt(Pools.MANUAL_NEEDS.size()));
            submit(userId, new CreateSupplyRequest(need[0], need[1], intent.quantityWish(), Urgency.valueOf(intent.urgency()),
                coordinate(place[0]), coordinate(place[1]), null, note, window[0], window[1], method, null));
            return;
        }
        // What the request form offers: free products with stock, largest amount at one point.
        Map<String, Product> products = new LinkedHashMap<>();
        for (InventoryItemView item : inventory.list()) {
            if (item.unitPriceFen() != 0) continue;
            products.merge(item.name(), new Product(item.id(), item.name(), item.category(), item.freeQuantity()),
                (a, b) -> b.maxFree() > a.maxFree() ? b : a);
        }
        List<Product> available = products.values().stream().filter(product -> product.maxFree() > 0).toList();
        if (available.isEmpty()) {
            // Nothing to pick in the form, so the resident asks what there is instead.
            chat(userId, new DailyActivityPlanner.ChatLine(DailyActivityPlanner.ChatTopic.INVENTORY, random.nextInt(8), 0));
            return;
        }
        // Each resident has their own favourites, ranked by a fixed per-resident score.
        List<Product> favourites = available.stream()
            .sorted(Comparator.comparingDouble(product -> ResidentPersona.unit(persona.favouriteSeed(), product.name().hashCode())))
            .limit(2 + Math.floorMod(persona.favouriteSeed(), 4)).toList();
        Product chosen = random.nextDouble() < 0.8
            ? favourites.get(random.nextInt(favourites.size()))
            : available.get(random.nextInt(available.size()));
        int quantity = Math.min(intent.quantityWish(), chosen.maxFree());
        submit(userId, new CreateSupplyRequest(chosen.category(), chosen.name(), quantity, Urgency.valueOf(intent.urgency()),
            coordinate(place[0]), coordinate(place[1]), null, note, window[0], window[1], method, chosen.itemId()));
    }

    private void submit(long userId, CreateSupplyRequest input) {
        submissions.submit(userId, UUID.randomUUID().toString(), input);
    }

    /** Home, or now and then somewhere a few hundred metres away. */
    private double[] place(long userId, DailyActivityPlanner.OrderIntent intent) {
        double[] home = home(userId);
        if (intent.awayMeters() <= 0) return home;
        double dLat = intent.awayMeters() * Math.cos(intent.awayBearing()) / 111_320;
        double dLon = intent.awayMeters() * Math.sin(intent.awayBearing()) / (111_320 * Math.cos(Math.toRadians(home[0])));
        return new double[] {home[0] + dLat, home[1] + dLon};
    }

    private double[] home(long userId) {
        return jdbc.sql("SELECT home_latitude, home_longitude FROM users WHERE id=:id").param("id", userId)
            .query((rs, n) -> rs.getBigDecimal("home_latitude") == null ? area.randomPoint()
                : new double[] {rs.getDouble("home_latitude"), rs.getDouble("home_longitude")}).single();
    }

    /** The booked slot as the request form builds it, or "as soon as possible" when it is already over. */
    static Instant[] window(DailyActivityPlanner.Booking booking, Instant now) {
        if (booking == null) return new Instant[] {null, null};
        LocalDate day = now.atZone(DailyActivityPlanner.ZONE).toLocalDate().plusDays(booking.daysAhead());
        Instant start = day.atTime(booking.morning() ? 9 : 13, 0).atZone(DailyActivityPlanner.ZONE).toInstant();
        Instant end = day.atTime(booking.morning() ? 12 : 17, 0).atZone(DailyActivityPlanner.ZONE).toInstant();
        return end.isAfter(now) ? new Instant[] {start, end} : new Instant[] {null, null};
    }

    private static BigDecimal coordinate(double degrees) {
        return BigDecimal.valueOf(degrees).setScale(6, RoundingMode.HALF_UP);
    }

    // ---- assistant ----

    private void chat(long userId, DailyActivityPlanner.ChatLine line) {
        List<String> questions = Pools.QUESTIONS.get(line.topic());
        String message = questions.get(line.variant() % questions.size());
        if (message.contains("{n}")) {
            int latest = jdbc.sql("SELECT COALESCE(MAX(resident_seq), 0) FROM supply_requests WHERE resident_id=:id")
                .param("id", userId).query(Integer.class).single();
            message = latest > 0 ? message.replace("{n}", String.valueOf(latest)) : "我的申请进度";
        }
        // A follow-up question continues the conversation the resident just had.
        String conversationId = line.seq() == 0 ? null : jdbc.sql("""
                SELECT id FROM assistant_conversations
                WHERE user_id=:id AND updated_at > CURRENT_TIMESTAMP(3) - INTERVAL 15 MINUTE
                ORDER BY updated_at DESC LIMIT 1
                """).param("id", userId).query(String.class).optional().orElse(null);
        double[] home = home(userId);
        assistant.chat(userId, new AssistantModels.ChatRequest(conversationId, message, null,
            coordinate(home[0]), coordinate(home[1]), null));
    }

    // ---- pickups ----

    private void collectDuePickups(long userId, Instant now) {
        ResidentPersona persona = ResidentPersona.of(userId);
        LocalTime clock = now.atZone(DailyActivityPlanner.ZONE).toLocalTime();
        List<DuePickup> held = jdbc.sql("""
                SELECT res.id, r.preferred_start, r.preferred_end, p.opens_at, p.closes_at
                FROM reservations res
                JOIN supply_requests r ON r.id = res.request_id
                JOIN inventory_items i ON i.id = res.inventory_item_id
                JOIN service_points p ON p.id = i.service_point_id
                WHERE res.resident_id=:id AND res.status IN ('HELD','CONFIRMED')
                  AND r.fulfillment_method='PICKUP' AND r.status IN ('APPROVED','SCHEDULED')
                  AND res.created_at < :settled
                """).param("id", userId).param("settled", Timestamp.from(now.minus(Duration.ofMinutes(20))))
            .query((rs, n) -> new DuePickup(rs.getLong("id"),
                rs.getTimestamp("preferred_start") == null ? null : rs.getTimestamp("preferred_start").toInstant(),
                rs.getTimestamp("preferred_end") == null ? null : rs.getTimestamp("preferred_end").toInstant(),
                rs.getObject("opens_at", LocalTime.class), rs.getObject("closes_at", LocalTime.class))).list();
        for (DuePickup pickup : held) {
            // Some residents never come; their code lapses and the stock is released as usual.
            if (ResidentPersona.unit(0x6e05_6f77L, pickup.reservationId()) < persona.noShowChance()) continue;
            boolean open = (pickup.opens() == null || !clock.isBefore(pickup.opens()))
                && (pickup.closes() == null || clock.isBefore(pickup.closes()));
            boolean inSlot = pickup.slotStart() == null || (!now.isBefore(pickup.slotStart()) && now.isBefore(pickup.slotEnd()));
            if (!open || !inSlot) continue;
            // Not everyone goes at the first chance.
            if (ResidentPersona.unit(now.toEpochMilli() / 60_000, pickup.reservationId()) < 0.3) continue;
            reservations.collectOnDelivery(system.id(), pickup.reservationId());
        }
    }

    // ---- feedback ----

    private void leaveFeedback(long userId, Instant now) {
        ResidentPersona persona = ResidentPersona.of(userId);
        List<Finished> finished = jdbc.sql("""
                SELECT r.id, r.fulfillment_method FROM supply_requests r
                WHERE r.resident_id=:id AND r.status='FULFILLED' AND r.updated_at > :since
                  AND NOT EXISTS (SELECT 1 FROM service_feedback f WHERE f.request_id = r.id)
                """).param("id", userId).param("since", Timestamp.from(now.minus(Duration.ofDays(4))))
            .query((rs, n) -> new Finished(rs.getLong("id"), rs.getString("fulfillment_method"))).list();
        for (Finished request : finished) {
            if (ResidentPersona.unit(0xfeedL, request.requestId()) >= persona.feedbackChance()) continue;
            SplittableRandom random = new SplittableRandom(ResidentPersona.mix(request.requestId(), 0xfeedL));
            double roll = random.nextDouble();
            int rating = roll < 0.55 ? 5 : roll < 0.85 ? 4 : roll < 0.95 ? 3 : roll < 0.98 ? 2 : 1;
            List<String> comments = Pools.FEEDBACK.get(rating);
            String comment = random.nextDouble() < 0.5 ? comments.get(random.nextInt(comments.size())) : null;
            Integer previous = null;
            Integer current = null;
            if (random.nextDouble() < 0.6) {
                previous = 20 + random.nextInt(71);
                current = "DELIVERY".equals(request.method()) ? 0 : 5 + random.nextInt(16);
            }
            recordFeedback(userId, request.requestId(), rating, comment, previous, current);
        }
    }

    /** The same rows the mini program's feedback page writes. */
    private void recordFeedback(long residentId, long requestId, int rating, String comments, Integer previous, Integer current) {
        try {
            transactions.executeWithoutResult(status -> insertFeedback(residentId, requestId, rating, comments, previous, current));
        } catch (DuplicateKeyException alreadyGiven) {
            // The resident left feedback in the meantime.
        }
    }

    private void insertFeedback(long residentId, long requestId, int rating, String comments, Integer previous, Integer current) {
        jdbc.sql("""
                INSERT INTO service_feedback
                  (request_id, resident_id, rating, comments, previous_travel_minutes, current_travel_minutes)
                VALUES (:requestId, :residentId, :rating, :comments, :previous, :current)
                """).param("requestId", requestId).param("residentId", residentId)
            .param("rating", rating).param("comments", comments)
            .param("previous", previous).param("current", current).update();
        jdbc.sql("""
                INSERT INTO impact_events (event_type, resident_id, request_id, value_number, metadata)
                VALUES ('SATISFACTION_RECORDED', :residentId, :requestId, :rating,
                  JSON_OBJECT('previousTravelMinutes', :previous, 'currentTravelMinutes', :current))
                """).param("residentId", residentId).param("requestId", requestId)
            .param("rating", rating).param("previous", previous).param("current", current).update();
    }

    record Resident(long id, Instant activatedAt) {
    }
    record Product(long itemId, String name, String category, int maxFree) {
    }
    record DuePickup(long reservationId, Instant slotStart, Instant slotEnd, LocalTime opens, LocalTime closes) {
    }
    record Finished(long requestId, String method) {
    }
}
