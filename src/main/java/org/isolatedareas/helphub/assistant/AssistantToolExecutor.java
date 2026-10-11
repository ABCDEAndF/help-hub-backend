package org.isolatedareas.helphub.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.isolatedareas.helphub.automation.CartTripService;
import org.isolatedareas.helphub.domain.FulfillmentMethod;
import org.isolatedareas.helphub.domain.RequestStatus;
import org.isolatedareas.helphub.domain.Urgency;
import org.isolatedareas.helphub.geo.AddressSearchService;
import org.isolatedareas.helphub.impact.FeedbackService;
import org.isolatedareas.helphub.inventory.InventoryItemView;
import org.isolatedareas.helphub.inventory.InventoryRepository;
import org.isolatedareas.helphub.inventory.ReservationService;
import org.isolatedareas.helphub.requests.CreateSupplyRequest;
import org.isolatedareas.helphub.requests.RequestService;
import org.isolatedareas.helphub.requests.ServiceWindow;
import org.isolatedareas.helphub.requests.SubmissionService;
import org.isolatedareas.helphub.requests.SupplyRequestRepository;
import org.isolatedareas.helphub.requests.SupplyRequestView;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Everything the assistant can look up or do for the signed-in resident: the same data and the same
 * actions as the mini program's pages. Lookups return data; an action returns a confirmation that runs
 * only once the resident agrees. Messages in exceptions are read by the model and passed on to the resident.
 */
@Service
public class AssistantToolExecutor {
    static final List<String> INVENTORY_CATEGORIES = List.of("FOOD", "WATER", "HYGIENE", "MEDICAL", "EMERGENCY", "OTHER");
    private static final ObjectMapper JSON = new ObjectMapper();

    private final InventoryRepository inventory;
    private final SupplyRequestRepository requests;
    private final ReservationService reservations;
    private final JdbcClient jdbc;
    private final ConfirmationService confirmations;
    private final Validator validator;
    private final SubmissionService submissions;
    private final RequestService requestService;
    private final FeedbackService feedback;
    private final CartTripService trips;
    private final AddressSearchService addresses;

    public AssistantToolExecutor(InventoryRepository inventory, SupplyRequestRepository requests,
                                 ReservationService reservations,
                                 JdbcClient jdbc, ConfirmationService confirmations,
                                 Validator validator, SubmissionService submissions, RequestService requestService,
                                 FeedbackService feedback, CartTripService trips, AddressSearchService addresses) {
        this.submissions = submissions;
        this.requestService = requestService;
        this.inventory = inventory;
        this.requests = requests;
        this.reservations = reservations;
        this.jdbc = jdbc;
        this.confirmations = confirmations;
        this.validator = validator;
        this.feedback = feedback;
        this.trips = trips;
        this.addresses = addresses;
    }

    public ToolResult execute(long userId, String tool, JsonNode args) {
        return switch (tool) {
            case "check_inventory" -> ToolResult.completed(byCategory(args.path("category").asText("")));
            // Residents speak of their own numbers (1, 2, 3 ...), never the internal id.
            case "get_request_status" -> ToolResult.completed(ownRequest(userId, requestNumber(args)));
            case "find_nearby_service_points" -> ToolResult.completed(findNearby(args));
            case "list_service_points" -> ToolResult.completed(listServicePoints(args));
            case "list_my_requests" -> ToolResult.completed(requests.findByResident(userId, 10, 0));
            case "list_my_reservations" -> ToolResult.completed(reservations.list(userId).stream().limit(10).toList());
            case "list_my_notifications" -> ToolResult.completed(notifications(userId));
            case "get_my_profile" -> ToolResult.completed(profile(userId));
            case "track_delivery" -> ToolResult.completed(tracking(userId, args));
            case "search_address" -> ToolResult.completed(searchAddress(requiredText(args, "address")));
            case "prepare_supply_request" -> prepareRequest(userId, args);
            case "reserve_pickup_slot" -> prepareReservation(userId, args);
            case "cancel_my_request" -> prepareCancellation(userId, args);
            case "submit_feedback" -> prepareFeedback(userId, args);
            case "update_my_profile" -> prepareProfile(userId, args);
            case "set_my_location" -> preparePlace(userId, args);
            default -> throw new IllegalArgumentException("Tool is not allowed: " + tool);
        };
    }

    public Object confirm(long userId, String token) {
        ConfirmationService.PendingAction action = confirmations.consume(userId, token);
        JsonNode args = action.arguments();
        return switch (action.tool()) {
            case "prepare_supply_request" -> createRequest(userId, args);
            case "reserve_pickup_slot" -> reservations.reserve(userId, reservationInput(args));
            case "cancel_my_request" -> requestService.cancelByResident(requiredLong(args, "requestId"), userId);
            case "submit_feedback" -> {
                feedback.record(userId, requiredLong(args, "requestId"), requiredInt(args, "rating"),
                    nullableText(args, "comments"), nullableInt(args, "previousTravelMinutes"),
                    nullableInt(args, "currentTravelMinutes"));
                yield new FeedbackGiven(requiredLong(args, "requestId"), requiredInt(args, "requestNumber"), requiredInt(args, "rating"));
            }
            case "update_my_profile" -> updateProfile(userId, requiredText(args, "displayName"), requiredInt(args, "householdSize"));
            case "set_my_location" -> new ChosenPlace(requiredDecimal(args, "latitude").doubleValue(),
                requiredDecimal(args, "longitude").doubleValue(), requiredText(args, "address"));
            default -> throw new IllegalArgumentException("Confirmed tool is not mutable or is no longer supported");
        };
    }

    /** What the confirmation of a tool does, for the resident to agree to. */
    public ConfirmationService.PendingAction pending(long userId, String token) {
        return confirmations.peek(userId, token);
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

    /** The resident's request by the number they know it by ("3号申请"). */
    private SupplyRequestView ownRequest(long userId, int number) {
        return requests.findOwnedByNumber(number, userId).orElseThrow(
            () -> new IllegalArgumentException("申请 #" + number + " 不存在，请向居民确认正确的申请编号"));
    }

    /** "requestId": 3, "3" or "#3". */
    private static int requestNumber(JsonNode args) {
        String digits = args.path("requestId").asText("").replaceAll("\\D", "");
        if (digits.isEmpty() || digits.length() > 9) throw new IllegalArgumentException("requestId is required");
        return Integer.parseInt(digits);
    }

    private static boolean hasRequestNumber(JsonNode args) {
        String digits = args.path("requestId").asText("").replaceAll("\\D", "");
        return !digits.isEmpty() && digits.length() <= 9;
    }

    // Words for "something" that name no item: asked about rather than filed.
    private static final java.util.Set<String> VAGUE_ITEMS = java.util.Set.of("物资", "东西", "物品", "用品", "生活用品", "日用品",
        "supplies", "supply", "stuff", "things", "something", "items", "item");

    private ToolResult prepareRequest(long userId, JsonNode original) {
        ObjectNode args = original.isObject() ? ((ObjectNode) original).deepCopy() : JSON.createObjectNode();
        // What the form insists on, asked for in the chat instead of guessed.
        String wanted = args.path("itemDescription").asText("").trim();
        if (wanted.isEmpty() || VAGUE_ITEMS.contains(wanted.toLowerCase())) throw new IllegalArgumentException("请问居民需要什么物资");
        if (!args.path("quantity").canConvertToInt() || args.path("quantity").asInt() < 1) {
            throw new IllegalArgumentException("请问居民需要多少（数量）");
        }
        if (args.path("quantity").asInt() > 100) throw new IllegalArgumentException("一次最多申请 100 份，请问居民要多少");
        String method = args.path("fulfillmentMethod").asText("").trim().toUpperCase();
        if (!method.equals("PICKUP") && !method.equals("DELIVERY")) {
            throw new IllegalArgumentException("请问居民是到服务点自取，还是要补给车配送");
        }
        // The stocked item is found here from what the resident asked for, so the model need not look
        // the stock up first, and an item id it made up is never stored.
        InventoryItemView item = stockedItem(args);
        if (item != null) {
            args.put("inventoryItemId", item.id()).put("itemDescription", item.name()).put("category", item.category());
        } else {
            args.remove("inventoryItemId");
            if (!INVENTORY_CATEGORIES.contains(args.path("category").asText("").trim().toUpperCase())) args.put("category", "OTHER");
        }
        if (!args.path("urgency").isTextual() || !java.util.Arrays.stream(Urgency.values())
            .anyMatch(level -> level.name().equalsIgnoreCase(args.path("urgency").asText().trim()))) {
            args.put("urgency", "NORMAL");
        }
        CreateSupplyRequest input = requestInput(args);
        // Reject an impossible slot now, so the resident is never asked to confirm it.
        ServiceWindow.validate(input.preferredStart(), input.preferredEnd(), Instant.now());
        // Outside the service area nothing can be submitted: say so now rather than after "确认".
        if (requestService.nearestServicePointMeters(input.latitude().doubleValue(), input.longitude().doubleValue())
            > RequestService.SERVICE_RADIUS_METERS) {
            throw new IllegalArgumentException("居民保存的位置离最近的服务点超过 10 公里，不在服务范围内，无法提交。请居民先把位置改成自己家的详细地址。");
        }
        String summary = "提交“" + requiredText(args, "itemDescription") + "”需求，数量 " + input.quantity()
            + "，" + (input.fulfillmentMethod() == FulfillmentMethod.PICKUP ? "到服务点自取" : "补给车配送")
            + "，" + (input.preferredStart() == null ? "尽快" : ServiceWindow.describe(input.preferredStart(), input.preferredEnd()))
            + "，紧急程度：" + urgencyName(input.urgency())
            + (input.inventoryItemId() != null ? "。提交后由工作人员审核，批准后发放领取码" : "。库存中没有该物资，工作人员审核后会另行联系");
        return ToolResult.confirmation(confirmations.create(userId, "prepare_supply_request", args, summary));
    }

    /**
     * The stock row for the item asked for: the given id when it exists, otherwise the one product whose
     * name matches the description (preferring a point that still has some free). Null when nothing in
     * stock matches; when several products do ("纸尿裤": adult or baby), the model must ask which.
     */
    private InventoryItemView stockedItem(ObjectNode args) {
        List<InventoryItemView> stock = inventory.list();
        if (args.path("inventoryItemId").canConvertToLong()) {
            long id = args.path("inventoryItemId").asLong();
            var given = stock.stream().filter(row -> row.id() == id).findFirst();
            if (given.isPresent()) return given.get();
        }
        String description = args.path("itemDescription").asText("").trim();
        List<InventoryItemView> matches = AssistantService.matchedItems(stock, description);
        // The resident's own words answer "which one?" ("the 24 bottle one", "第二种") when the model
        // passes on only the general name.
        List<String> names = narrowed(matches.stream().map(InventoryItemView::name).distinct().toList(),
            description, args.path("residentWords").asText(""));
        if (names.size() > 1) throw ResidentQuestion.whichItem(description, names);
        if (names.isEmpty()) return null;
        return matches.stream().filter(row -> row.name().equals(names.get(0))).filter(row -> row.freeQuantity() > 0)
            .findFirst().orElse(matches.stream().filter(row -> row.name().equals(names.get(0))).findFirst().orElseThrow());
    }

    /**
     * Of several products by the same name ("公益饮用水 12 瓶装", "公益饮用水 550 毫升×24 瓶"), those the
     * description singles out: the one named in full, or those with every number it mentions ("24瓶的").
     */
    static List<String> narrowed(List<String> names, String description) {
        return narrowed(names, description, "");
    }

    /**
     * As above, also reading the resident's latest words: a number or size in them, or which of the
     * listed choices they picked ("第二种", "the second one", "2"), in the order the choices were listed.
     */
    static List<String> narrowed(List<String> names, String description, String residentWords) {
        if (names.size() < 2) return names;
        for (String text : List.of(description, residentWords == null ? "" : residentWords)) {
            List<String> whole = names.stream().filter(text::contains).toList();
            if (whole.size() == 1) return whole;
            // Whole numbers only: "1 case" is a quantity, not the "1" in "12 瓶装".
            List<String> numbers = numbersIn(text);
            if (!numbers.isEmpty()) {
                List<String> withNumbers = names.stream().filter(name -> numbersIn(name).containsAll(numbers)).toList();
                if (withNumbers.size() == 1) return withNumbers;
                List<String> withAny = names.stream().filter(name -> numbers.stream().anyMatch(numbersIn(name)::contains)).toList();
                if (withAny.size() == 1) return withAny;
            }
        }
        int picked = ordinal(residentWords);
        return picked >= 1 && picked <= names.size() ? List.of(names.get(picked - 1)) : names;
    }

    private static List<String> numbersIn(String text) {
        return java.util.regex.Pattern.compile("\\d+").matcher(text).results().map(java.util.regex.MatchResult::group).toList();
    }

    private static final java.util.regex.Pattern ORDINAL_ZH = java.util.regex.Pattern.compile("第\\s*([一二三四五六七八九十]|\\d+)");
    private static final java.util.regex.Pattern ORDINAL_EN = java.util.regex.Pattern.compile(
        "\\b(first|second|third|fourth|fifth|1st|2nd|3rd|4th|5th)\\b", java.util.regex.Pattern.CASE_INSENSITIVE);

    /** "第二种" / "the second one" / "2" -> 2; 0 when the words pick no position. */
    static int ordinal(String words) {
        if (words == null) return 0;
        String text = words.trim();
        var zh = ORDINAL_ZH.matcher(text);
        if (zh.find()) {
            String n = zh.group(1);
            return n.matches("\\d+") ? Integer.parseInt(n) : "一二三四五六七八九十".indexOf(n) + 1;
        }
        var en = ORDINAL_EN.matcher(text);
        if (en.find()) {
            return switch (en.group(1).toLowerCase()) {
                case "first", "1st" -> 1;
                case "second", "2nd" -> 2;
                case "third", "3rd" -> 3;
                case "fourth", "4th" -> 4;
                default -> 5;
            };
        }
        return text.matches("\\d") ? Integer.parseInt(text) : 0;
    }

    private ToolResult prepareCancellation(long userId, JsonNode args) {
        SupplyRequestView request;
        if (hasRequestNumber(args)) {
            request = ownRequest(userId, requestNumber(args));
        } else {
            // No number given: the only request still waiting for review, or a list to ask from.
            List<SupplyRequestView> waiting = requests.findByResident(userId, 50, 0).stream()
                .filter(row -> RequestService.cancellableByResident(row.status())).toList();
            if (waiting.isEmpty()) throw new IllegalArgumentException("居民目前没有等待审核、可以取消的申请。" + RequestService.NOT_CANCELLABLE);
            if (waiting.size() > 1) {
                throw new IllegalArgumentException("居民有多个等待审核的申请：" + String.join("、", waiting.stream()
                    .map(row -> "#" + row.residentNumber() + " " + row.itemDescription() + " × " + row.quantity()).toList())
                    + "。请问居民要取消哪一个。");
            }
            request = waiting.get(0);
        }
        if (!RequestService.cancellableByResident(request.status())) {
            throw new IllegalArgumentException("申请 #" + request.residentNumber() + " 当前不是待审核状态。" + RequestService.NOT_CANCELLABLE);
        }
        // The confirmed action runs later from the stored arguments, so store the internal id.
        var resolved = JSON.createObjectNode().put("requestId", request.id());
        String summary = "取消申请 #" + request.residentNumber() + "：" + request.itemDescription() + " × " + request.quantity();
        return ToolResult.confirmation(confirmations.create(userId, "cancel_my_request", resolved, summary));
    }

    private ToolResult prepareReservation(long userId, JsonNode args) {
        // Check ownership and state before the resident is asked to agree. Without this the
        // resident is asked to approve an action built from a number the model invented, and only
        // learns it was never theirs after agreeing.
        SupplyRequestView request = ownRequest(userId, requestNumber(args));
        int number = request.residentNumber();
        if (request.status() != RequestStatus.APPROVED && request.status() != RequestStatus.SCHEDULED) {
            throw new IllegalArgumentException("申请 #" + number + " 尚未批准，暂时不能预约");
        }
        int quantity = args.path("quantity").canConvertToInt() && args.path("quantity").asInt() > 0
            ? args.path("quantity").asInt() : request.quantity();
        InventoryItemView item = reservableItem(args, request, quantity);
        var resolved = JSON.createObjectNode().put("requestId", request.id()).put("inventoryItemId", item.id())
            .put("quantity", quantity);
        reservationInput(resolved);
        String summary = "为申请 #" + number + " 预约 " + item.name() + "（" + item.servicePointName() + "），数量 " + quantity;
        return ToolResult.confirmation(confirmations.create(userId, "reserve_pickup_slot", resolved, summary));
    }

    /** The stock to reserve: the item named, else the request's own item, at a point that has enough free. */
    private InventoryItemView reservableItem(JsonNode args, SupplyRequestView request, int quantity) {
        List<InventoryItemView> stock = inventory.list();
        List<InventoryItemView> candidates;
        if (args.path("inventoryItemId").canConvertToLong()
            && stock.stream().anyMatch(row -> row.id() == args.path("inventoryItemId").asLong())) {
            long id = args.path("inventoryItemId").asLong();
            candidates = stock.stream().filter(row -> row.id() == id).toList();
        } else {
            String wanted = args.path("item").asText("").isBlank() ? request.itemDescription() : args.path("item").asText();
            List<String> names = narrowed(AssistantService.matchedItems(stock, wanted).stream().map(InventoryItemView::name)
                .distinct().toList(), wanted, args.path("residentWords").asText(""));
            candidates = AssistantService.matchedItems(stock, wanted).stream().filter(row -> names.contains(row.name())).toList();
            if (names.size() > 1) throw ResidentQuestion.whichItem(wanted, names);
            if (names.isEmpty()) {
                // Reservations are made within the request's category, as on the reservations page.
                candidates = stock.stream().filter(row -> row.category().equalsIgnoreCase(request.category())).toList();
                if (candidates.isEmpty()) throw new IllegalArgumentException("库存中没有能为申请 #" + request.residentNumber() + " 预约的物资");
                List<String> sameCategory = candidates.stream().filter(row -> row.freeQuantity() > 0)
                    .map(InventoryItemView::name).distinct().toList();
                throw new IllegalArgumentException("请问居民要预约哪种物资，同类可选：" + String.join("、", sameCategory));
            }
        }
        InventoryItemView first = candidates.get(0);
        if (!first.category().equalsIgnoreCase(request.category())) {
            throw new IllegalArgumentException("申请 #" + request.residentNumber() + " 只能预约同一类别的物资");
        }
        return candidates.stream().filter(row -> row.freeQuantity() >= quantity).findFirst()
            .orElseThrow(() -> new IllegalArgumentException(first.name() + " 目前没有一个服务点能提供 " + quantity
                + " 份，请减少数量或换一种物资"));
    }

    private ToolResult prepareFeedback(long userId, JsonNode args) {
        SupplyRequestView request = ownRequest(userId, requestNumber(args));
        int number = request.residentNumber();
        if (request.status() != RequestStatus.FULFILLED) {
            throw new IllegalArgumentException("申请 #" + number + " 还没有完成，完成后才能评价");
        }
        if (feedback.rated(request.id())) throw new IllegalArgumentException("申请 #" + number + " 已经评价过了，每个申请只能评价一次");
        if (!args.path("rating").canConvertToInt() || args.path("rating").asInt() < 1 || args.path("rating").asInt() > 5) {
            throw new IllegalArgumentException("请先问居民给这次服务打几星（1 到 5 星）");
        }
        int rating = args.path("rating").asInt();
        Integer previous = minutes(args, "previousTravelMinutes");
        Integer current = minutes(args, "currentTravelMinutes");
        String comments = nullableText(args, "comments");
        if (comments != null && comments.length() > 1000) comments = comments.substring(0, 1000);
        ObjectNode resolved = JSON.createObjectNode().put("requestId", request.id()).put("requestNumber", number)
            .put("rating", rating);
        if (previous != null) resolved.put("previousTravelMinutes", previous);
        if (current != null) resolved.put("currentTravelMinutes", current);
        if (comments != null && !comments.isBlank()) resolved.put("comments", comments.trim());
        String summary = "为申请 #" + number + " 提交服务反馈：" + rating + " 星"
            + (previous != null ? "，以前获取同类物资约 " + previous + " 分钟" : "")
            + (current != null ? "，这次约 " + current + " 分钟" : "")
            + (comments != null && !comments.isBlank() ? "，意见：" + comments.trim() : "");
        return ToolResult.confirmation(confirmations.create(userId, "submit_feedback", resolved, summary));
    }

    private static Integer minutes(JsonNode args, String field) {
        if (!args.hasNonNull(field)) return null;
        if (!args.path(field).canConvertToInt() || args.path(field).asInt() < 0) {
            throw new IllegalArgumentException("出行时间应为不小于 0 的整数分钟");
        }
        return args.path(field).asInt();
    }

    private ToolResult prepareProfile(long userId, JsonNode args) {
        Profile current = profile(userId);
        String name = args.path("displayName").asText("").trim();
        if (name.isEmpty()) name = current.displayName();
        if (name.length() > 100) throw new IllegalArgumentException("称呼最多 100 个字");
        int household = current.householdSize();
        if (args.hasNonNull("householdSize")) {
            if (!args.path("householdSize").canConvertToInt() || args.path("householdSize").asInt() < 1
                || args.path("householdSize").asInt() > 20) {
                throw new IllegalArgumentException("家庭人数应在 1 到 20 人之间");
            }
            household = args.path("householdSize").asInt();
        }
        if (name.equals(current.displayName()) && household == current.householdSize()) {
            throw new IllegalArgumentException("资料没有变化：称呼仍是“" + name + "”，家庭人数仍是 " + household + " 人。请问居民要改成什么");
        }
        var resolved = JSON.createObjectNode().put("displayName", name).put("householdSize", household);
        String summary = "把资料改为：称呼“" + name + "”，家庭人数 " + household + " 人";
        return ToolResult.confirmation(confirmations.create(userId, "update_my_profile", resolved, summary));
    }

    /** Saving a place found by address search; the client stores it, as the place page does. */
    private ToolResult preparePlace(long userId, JsonNode args) {
        double latitude = requiredDecimal(args, "latitude").doubleValue();
        double longitude = requiredDecimal(args, "longitude").doubleValue();
        String address = requiredText(args, "address");
        long meters = Math.round(requestService.nearestServicePointMeters(latitude, longitude));
        var resolved = JSON.createObjectNode().put("latitude", latitude).put("longitude", longitude).put("address", address);
        String summary = "把您的位置设为“" + address + "”（" + args.path("title").asText(address) + "），离最近的服务点约 "
            + String.format(java.util.Locale.ROOT, "%.1f", meters / 1000.0) + " 公里";
        return ToolResult.confirmation(confirmations.create(userId, "set_my_location", resolved, summary));
    }

    private Object createRequest(long userId, JsonNode args) {
        return submissions.submit(userId, null, requestInput(args));
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
        String method = args.path("fulfillmentMethod").asText("").trim().toUpperCase();
        return valid(new CreateSupplyRequest(
            requiredText(args, "category"), requiredText(args, "itemDescription"), requiredInt(args, "quantity"),
            Urgency.valueOf(args.path("urgency").asText("NORMAL").trim().toUpperCase()),
            requiredDecimal(args, "latitude"), requiredDecimal(args, "longitude"),
            nullableText(args, "approximateAddress"), nullableText(args, "accessibilityNotes"),
            nullableInstant(args, "preferredStart"), nullableInstant(args, "preferredEnd"),
            "PICKUP".equals(method) || "DELIVERY".equals(method) ? FulfillmentMethod.valueOf(method) : null,
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
                SELECT id, name, address, latitude, longitude, opens_at, closes_at,
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
                point.put("opensAt", rs.getObject("opens_at", LocalTime.class));
                point.put("closesAt", rs.getObject("closes_at", LocalTime.class));
                return point;
            }).list();
    }

    // Answers "is there a service point in 浦东?" without coordinates. Without it the model
    // has no way to serve a place-name question except by inventing a latitude/longitude.
    private Object listServicePoints(JsonNode args) {
        String keyword = args.path("keyword").asText("").trim();
        return jdbc.sql("""
                SELECT id, name, address, status, latitude, longitude, opens_at, closes_at FROM service_points
                WHERE status='ACTIVE' AND (:keyword='' OR name LIKE :like OR address LIKE :like)
                ORDER BY name LIMIT 50
                """).param("keyword", keyword).param("like", "%" + keyword + "%")
            .query((rs, n) -> {
                Map<String, Object> point = new LinkedHashMap<>();
                point.put("id", rs.getLong("id"));
                point.put("name", rs.getString("name"));
                point.put("address", rs.getString("address"));
                point.put("status", rs.getString("status"));
                point.put("latitude", rs.getBigDecimal("latitude"));
                point.put("longitude", rs.getBigDecimal("longitude"));
                point.put("opensAt", rs.getObject("opens_at", LocalTime.class));
                point.put("closesAt", rs.getObject("closes_at", LocalTime.class));
                return point;
            }).list();
    }

    /** The resident's in-app notices, newest first, as the notices page lists them. */
    private List<Notice> notifications(long userId) {
        return jdbc.sql("""
                SELECT template_code, payload, created_at FROM notification_deliveries
                WHERE recipient_user_id=:userId AND channel='IN_APP' ORDER BY created_at DESC LIMIT 10
                """).param("userId", userId)
            .query((rs, n) -> new Notice(rs.getString("template_code"), payload(rs.getString("payload")),
                rs.getTimestamp("created_at").toInstant())).list();
    }

    private static JsonNode payload(String value) {
        try {
            return value == null ? JSON.createObjectNode() : JSON.readTree(value);
        } catch (Exception unreadable) {
            return JSON.createObjectNode();
        }
    }

    private Profile profile(long userId) {
        return jdbc.sql("SELECT display_name, household_size FROM users WHERE id=:id").param("id", userId)
            .query((rs, n) -> new Profile(rs.getString("display_name"), rs.getInt("household_size"))).single();
    }

    private Profile updateProfile(long userId, String name, int household) {
        jdbc.sql("UPDATE users SET display_name=:name, household_size=:size WHERE id=:id AND role='RESIDENT'")
            .param("name", name.trim()).param("size", household).param("id", userId).update();
        return profile(userId);
    }

    /** The cart bringing a request: the one named, else the resident's latest delivery still under way. */
    private Delivery tracking(long userId, JsonNode args) {
        SupplyRequestView request;
        if (hasRequestNumber(args)) {
            request = ownRequest(userId, requestNumber(args));
        } else {
            List<SupplyRequestView> deliveries = requests.findByResident(userId, 50, 0).stream()
                .filter(row -> row.fulfillmentMethod() == FulfillmentMethod.DELIVERY).toList();
            request = deliveries.stream().filter(row -> row.status() == RequestStatus.APPROVED || row.status() == RequestStatus.SCHEDULED)
                .findFirst().orElse(deliveries.isEmpty() ? null : deliveries.get(0));
            if (request == null) return new Delivery(null, null);
        }
        Optional<CartTripService.Tracking> trip = request.fulfillmentMethod() == FulfillmentMethod.DELIVERY
            ? trips.tracking(request.id(), userId, Instant.now()) : Optional.empty();
        return new Delivery(request, trip.orElse(null));
    }

    /** Places matching a typed address, each with how far the nearest service point is. */
    private AddressMatches searchAddress(String address) {
        if (!addresses.available()) return new AddressMatches(address, false, List.of());
        String text = address.length() > 80 ? address.substring(0, 80) : address;
        List<FoundPlace> found = addresses.search(text).stream()
            .filter(place -> place.latitude() >= 30 && place.latitude() <= 32 && place.longitude() >= 120 && place.longitude() <= 123)
            .limit(5)
            .map(place -> new FoundPlace(place.title(), place.address(), place.latitude(), place.longitude(),
                Math.round(requestService.nearestServicePointMeters(place.latitude(), place.longitude()))))
            .toList();
        return new AddressMatches(address, true, found);
    }

    private long requiredLong(JsonNode args, String field) {
        if (!args.has(field) || !args.path(field).canConvertToLong()) throw new IllegalArgumentException(field + " is required");
        return args.path(field).asLong();
    }
    private int requiredInt(JsonNode args, String field) {
        if (!args.has(field) || !args.path(field).canConvertToInt()) throw new IllegalArgumentException(field + " is required");
        return args.path(field).asInt();
    }
    private static Integer nullableInt(JsonNode args, String field) {
        return args.hasNonNull(field) && args.path(field).canConvertToInt() ? args.path(field).asInt() : null;
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
    private static String nullableText(JsonNode args, String field) {
        return args.hasNonNull(field) && !args.path(field).asText().isBlank() ? args.path(field).asText() : null;
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

    public record Notice(String template, JsonNode payload, Instant createdAt) {
    }

    public record Profile(String displayName, int householdSize) {
    }

    /** request is null when the resident has no delivery request; trip is null before a cart is on its way. */
    public record Delivery(SupplyRequestView request, CartTripService.Tracking trip) {
    }

    public record FoundPlace(String title, String address, double latitude, double longitude, long nearestPointMeters) {
    }

    public record AddressMatches(String query, boolean available, List<FoundPlace> places) {
    }

    public record FeedbackGiven(long requestId, int requestNumber, int rating) {
    }

    public record ChosenPlace(double latitude, double longitude, String address) {
    }
}
