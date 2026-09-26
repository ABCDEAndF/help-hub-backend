package org.isolatedareas.helphub.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.isolatedareas.helphub.automation.AutoDecisionService;
import org.isolatedareas.helphub.domain.FulfillmentMethod;
import org.isolatedareas.helphub.domain.RequestStatus;
import org.isolatedareas.helphub.domain.Urgency;
import org.isolatedareas.helphub.inventory.InventoryItemView;
import org.isolatedareas.helphub.inventory.InventoryRepository;
import org.isolatedareas.helphub.inventory.ReservationService;
import org.isolatedareas.helphub.requests.CreateSupplyRequest;
import org.isolatedareas.helphub.requests.ServiceWindow;
import org.isolatedareas.helphub.requests.RequestService;
import org.isolatedareas.helphub.requests.SupplyRequestRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
public class AssistantToolExecutor {
    static final List<String> INVENTORY_CATEGORIES = List.of("FOOD", "WATER", "HYGIENE", "MEDICAL", "EMERGENCY", "OTHER");

    private final InventoryRepository inventory;
    private final SupplyRequestRepository requests;
    private final RequestService requestService;
    private final ReservationService reservations;
    private final JdbcClient jdbc;
    private final ConfirmationService confirmations;
    private final Validator validator;
    private final AutoDecisionService decisions;

    public AssistantToolExecutor(InventoryRepository inventory, SupplyRequestRepository requests,
                                 RequestService requestService, ReservationService reservations,
                                 JdbcClient jdbc, ConfirmationService confirmations,
                                 Validator validator, AutoDecisionService decisions) {
        this.decisions = decisions;
        this.inventory = inventory;
        this.requests = requests;
        this.requestService = requestService;
        this.reservations = reservations;
        this.jdbc = jdbc;
        this.confirmations = confirmations;
        this.validator = validator;
    }

    public ToolResult execute(long userId, String tool, JsonNode args) {
        return switch (tool) {
            case "check_inventory" -> ToolResult.completed(byCategory(args.path("category").asText("")));
            // Residents speak of their own numbers (1, 2, 3 ...), never the internal id.
            case "get_request_status" -> ToolResult.completed(requests.findOwnedByNumber((int) requiredLong(args, "requestId"), userId)
                .orElseThrow(() -> new IllegalArgumentException("Request was not found")));
            case "find_nearby_service_points" -> ToolResult.completed(findNearby(args));
            case "list_service_points" -> ToolResult.completed(listServicePoints(args));
            case "list_my_requests" -> ToolResult.completed(requests.findByResident(userId, 5, 0));
            case "list_my_reservations" -> ToolResult.completed(reservations.list(userId).stream().limit(5).toList());
            case "prepare_supply_request" -> prepareRequest(userId, args);
            case "reserve_pickup_slot" -> prepareReservation(userId, args);
            default -> throw new IllegalArgumentException("Tool is not allowed: " + tool);
        };
    }

    public Object confirm(long userId, String token) {
        ConfirmationService.PendingAction action = confirmations.consume(userId, token);
        return switch (action.tool()) {
            case "prepare_supply_request" -> createRequest(userId, action.arguments());
            case "reserve_pickup_slot" -> reservations.reserve(userId, reservationInput(action.arguments()));
            default -> throw new IllegalArgumentException("Confirmed tool is not mutable or is no longer supported");
        };
    }

    // Models routinely pass a catch-all such as "all" or "" when the caller asked for
    // everything, so anything outside the known set means "do not filter" rather than
    // "match nothing".
    private List<InventoryItemView> byCategory(String category) {
        String normalized = category.trim().toUpperCase();
        List<InventoryItemView> items = inventory.list();
        if (!INVENTORY_CATEGORIES.contains(normalized)) return items;
        return items.stream().filter(item -> normalized.equalsIgnoreCase(item.category())).toList();
    }

    private ToolResult prepareRequest(long userId, JsonNode args) {
        CreateSupplyRequest input = requestInput(args);
        // Reject an impossible slot now, so the resident is never asked to confirm it.
        ServiceWindow.validate(input.preferredStart(), input.preferredEnd(), Instant.now());
        String summary = "提交“" + requiredText(args, "itemDescription") + "”需求，数量 " + input.quantity()
            + "，" + (input.fulfillmentMethod() == FulfillmentMethod.PICKUP ? "到服务点自取" : "补给车配送")
            + "，" + (input.preferredStart() == null ? "尽快" : ServiceWindow.describe(input.preferredStart(), input.preferredEnd()))
            + "，紧急程度：" + urgencyName(input.urgency())
            + (input.inventoryItemId() != null ? "。库存足够会立即自动批准并发放领取码" : "。库存中没有该物资，将转人工处理");
        return ToolResult.confirmation(confirmations.create(userId, "prepare_supply_request", args, summary));
    }

    private ToolResult prepareReservation(long userId, JsonNode args) {
        ReservationService.ReserveInput input = reservationInput(args);
        // Check ownership and state before a confirmation card is shown. Without this the
        // resident is asked to approve a card built from a number the model invented, and only
        // learns it was never theirs after pressing confirm.
        int number = (int) input.requestId();
        var request = requests.findOwnedByNumber(number, userId).orElseThrow(
            () -> new IllegalArgumentException("申请 #" + number + " 不存在，请向用户确认正确的申请编号"));
        if (request.status() != RequestStatus.APPROVED && request.status() != RequestStatus.SCHEDULED) {
            throw new IllegalArgumentException("申请 #" + number + " 尚未批准，暂时不能预约");
        }
        String itemName = inventory.find(input.inventoryItemId()).map(item -> item.name() + "（" + item.servicePointName() + "）")
            .orElseThrow(() -> new IllegalArgumentException("库存编号 " + input.inventoryItemId() + " 不存在"));
        // The confirmed action runs later from the stored arguments, so store the internal id.
        var resolved = ((com.fasterxml.jackson.databind.node.ObjectNode) args.deepCopy()).put("requestId", request.id());
        String summary = "为申请 #" + number + " 预约 " + itemName + "，数量 " + input.quantity();
        return ToolResult.confirmation(confirmations.create(userId, "reserve_pickup_slot", resolved, summary));
    }

    private Object createRequest(long userId, JsonNode args) {
        return decisions.decide(requestService.create(userId, requestInput(args)).id());
    }

    private static String urgencyName(Urgency urgency) {
        return switch (urgency) {
            case LOW -> "不紧急";
            case NORMAL -> "一般";
            case HIGH -> "较紧急";
            case CRITICAL -> "非常紧急";
        };
    }

    private CreateSupplyRequest requestInput(JsonNode args) {
        return valid(new CreateSupplyRequest(
            requiredText(args, "category"), requiredText(args, "itemDescription"), requiredInt(args, "quantity"),
            Urgency.valueOf(args.path("urgency").asText("NORMAL").toUpperCase()),
            requiredDecimal(args, "latitude"), requiredDecimal(args, "longitude"),
            nullableText(args, "approximateAddress"), nullableText(args, "accessibilityNotes"),
            nullableInstant(args, "preferredStart"), nullableInstant(args, "preferredEnd"),
            args.hasNonNull("fulfillmentMethod")
                ? FulfillmentMethod.valueOf(args.path("fulfillmentMethod").asText().toUpperCase()) : null,
            args.hasNonNull("inventoryItemId") && args.path("inventoryItemId").canConvertToLong()
                ? args.path("inventoryItemId").asLong() : null));
    }

    private ReservationService.ReserveInput reservationInput(JsonNode args) {
        return valid(new ReservationService.ReserveInput(requiredLong(args, "requestId"),
            requiredLong(args, "inventoryItemId"), requiredInt(args, "quantity")));
    }

    private <T> T valid(T input) {
        var violations = validator.validate(input);
        if (!violations.isEmpty()) throw new ConstraintViolationException(violations);
        return input;
    }

    private Object findNearby(JsonNode args) {
        BigDecimal latitude = requiredDecimal(args, "latitude");
        BigDecimal longitude = requiredDecimal(args, "longitude");
        int radius = Math.min(Math.max(args.path("radiusMeters").asInt(5000), 100), 50_000);
        return jdbc.sql("""
                SELECT id, name, address, latitude, longitude,
                  ST_Distance_Sphere(POINT(longitude, latitude), POINT(:longitude, :latitude)) AS distance_meters
                FROM service_points WHERE status='ACTIVE'
                HAVING distance_meters <= :radius ORDER BY distance_meters LIMIT 10
                """).param("latitude", latitude).param("longitude", longitude).param("radius", radius)
            .query((rs, n) -> {
                Map<String, Object> point = new LinkedHashMap<>();
                point.put("id", rs.getLong("id"));
                point.put("name", rs.getString("name"));
                point.put("address", rs.getString("address"));
                point.put("latitude", rs.getBigDecimal("latitude"));
                point.put("longitude", rs.getBigDecimal("longitude"));
                point.put("distanceMeters", Math.round(rs.getDouble("distance_meters")));
                return point;
            }).list();
    }

    // Answers "is there a service point in 浦东?" without coordinates. Without it the model
    // has no way to serve a place-name question except by inventing a latitude/longitude.
    private Object listServicePoints(JsonNode args) {
        String keyword = args.path("keyword").asText("").trim();
        return jdbc.sql("""
                SELECT id, name, address, status, opens_at, closes_at FROM service_points
                WHERE status='ACTIVE' AND (:keyword='' OR name LIKE :like OR address LIKE :like)
                ORDER BY name LIMIT 50
                """).param("keyword", keyword).param("like", "%" + keyword + "%")
            .query((rs, n) -> {
                Map<String, Object> point = new LinkedHashMap<>();
                point.put("id", rs.getLong("id"));
                point.put("name", rs.getString("name"));
                point.put("address", rs.getString("address"));
                point.put("status", rs.getString("status"));
                point.put("opensAt", rs.getObject("opens_at", LocalTime.class));
                point.put("closesAt", rs.getObject("closes_at", LocalTime.class));
                return point;
            }).list();
    }

    private long requiredLong(JsonNode args, String field) {
        if (!args.has(field) || !args.path(field).canConvertToLong()) throw new IllegalArgumentException(field + " is required");
        return args.path(field).asLong();
    }
    private int requiredInt(JsonNode args, String field) {
        if (!args.has(field) || !args.path(field).canConvertToInt()) throw new IllegalArgumentException(field + " is required");
        return args.path(field).asInt();
    }
    private String requiredText(JsonNode args, String field) {
        String value = args.path(field).asText("").trim();
        if (value.isEmpty()) throw new IllegalArgumentException(field + " is required");
        return value;
    }
    private BigDecimal requiredDecimal(JsonNode args, String field) {
        if (!args.has(field) || !args.path(field).isNumber()) throw new IllegalArgumentException(field + " is required");
        return args.path(field).decimalValue();
    }
    private String nullableText(JsonNode args, String field) {
        return args.hasNonNull(field) ? args.path(field).asText() : null;
    }
    // Models write times with or without a zone; a bare local time means Beijing time.
    private Instant nullableInstant(JsonNode args, String field) {
        if (!args.hasNonNull(field) || args.path(field).asText().isBlank()) return null;
        String text = args.path(field).asText().trim();
        try {
            return java.time.OffsetDateTime.parse(text).toInstant();
        } catch (java.time.format.DateTimeParseException withoutZone) {
            return java.time.LocalDateTime.parse(text).atZone(ServiceWindow.ZONE).toInstant();
        }
    }

    public record ToolResult(Object result, AssistantModels.Confirmation confirmation) {
        static ToolResult completed(Object result) { return new ToolResult(result, null); }
        static ToolResult confirmation(AssistantModels.Confirmation confirmation) { return new ToolResult(null, confirmation); }
    }
}
