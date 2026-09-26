package org.isolatedareas.helphub.assistant;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.isolatedareas.helphub.domain.FulfillmentMethod;
import org.isolatedareas.helphub.inventory.InventoryItemView;
import org.isolatedareas.helphub.inventory.ReservationService;
import org.isolatedareas.helphub.requests.SupplyRequestView;

@Service
public class AssistantService {
    private static final Pattern REQUEST_ID = Pattern.compile(
        "(?:申请|单子|订单|工单|request|#)\\s*#?\\s*(\\d+)|(\\d+)\\s*号?\\s*(?:申请|单子|订单|工单)",
        Pattern.CASE_INSENSITIVE);
    private static final String SYSTEM_PROMPT = """
        你是 Isolated Areas Help Hub 的公益服务助手。使用简明中文回答。
        库存、服务点和申请状态只能通过工具查询，绝不猜测。
        工具已按当前登录居民的身份鉴权，你有权代表其查询本人数据；
        绝不能以隐私、权限或“请联系官方渠道”为由拒绝调用工具。
        用户给出申请编号时必须调用 get_request_status；未给出编号而询问“我的申请/进度”时调用 list_my_requests。
        询问预约、领取码时调用 list_my_reservations。
        口语中的“单子”“订单”“工单”“号”都指申请，其后的数字即申请编号，例如“17号单子”就是申请 17。
        用户询问物资或库存时必须调用 check_inventory，不要反问“需要我调用工具吗”。
        绝不编造任何编号或坐标。申请编号、库存编号、经纬度都只能来自用户或此前的工具结果；
        缺少其中任何一项时，先向用户索要，不要凭空填入数值去调用工具。
        地名（如“浦东”）不是坐标：按地名找服务点用 list_service_points，
        只有拿到用户给出的经纬度才能调用 find_nearby_service_points 计算距离。
        库存结果中 unitPriceFen 为 0 即表示免费领取，freeQuantity 为可领数量。
        用户要求提交需求或预约物资时，直接调用对应工具，不要用文字复述参数并征求同意；
        确认环节由系统统一处理，你的文字确认不具备任何效力。
        工具返回 error 字段时，向用户说明失败原因并请其补充正确信息，不要重复调用同一工具。
        提交需求前先调用 check_inventory：库存中有该物资时，把对应的 id 作为 inventoryItemId 传入，
        系统会立即自动审批——库存足够就批准并锁定库存、发放领取码，不足则拒绝；库存中没有的物资不传 inventoryItemId，会转人工处理。
        提交需求还需要领取方式（PICKUP 到服务点自取 / DELIVERY 补给车配送）和居民位置经纬度；缺少时先询问。
        配送由系统自动调度最近的补给车：先到服务点取货再送达，居民可在地图上看到车辆位置和预计到达时间。
        领取码保留 48 小时；到点自取需在服务点营业时间内前往。
        不提供医疗诊断或用药建议。遇到人身危险或紧急医疗需求，建议联系 120、110 或当地应急服务。
        只处理日常必需品、服务点、申请、预约和项目流程相关问题，其他话题礼貌拒绝。
        """;

    private final OpenAiCompatibleClient model;
    private final AssistantToolExecutor tools;
    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final int maxToolRounds;

    public AssistantService(OpenAiCompatibleClient model, AssistantToolExecutor tools, JdbcClient jdbc,
                            ObjectMapper json, @Value("${app.ai.max-tool-rounds}") int maxToolRounds) {
        this.model = model;
        this.tools = tools;
        this.jdbc = jdbc;
        this.json = json;
        this.maxToolRounds = maxToolRounds;
    }

    public AssistantModels.ChatResponse chat(long userId, AssistantModels.ChatRequest input) {
        String conversationId = ensureConversation(userId, input.conversationId());
        if (input.confirmationToken() != null && !input.confirmationToken().isBlank()) {
            Object result = tools.confirm(userId, input.confirmationToken());
            String content = result instanceof SupplyRequestView request
                ? "已提交申请 #" + request.id() + "。" + (request.decisionNote() == null ? "" : request.decisionNote())
                : "操作已确认并完成。";
            saveMessage(conversationId, "USER", input.message(), null);
            saveMessage(conversationId, "ASSISTANT", content, null);
            return new AssistantModels.ChatResponse(conversationId, content,
                List.of(new AssistantModels.ToolExecution("confirmed_action", result)), null, model.configured());
        }

        saveMessage(conversationId, "USER", input.message(), null);
        if (!model.configured()) return fallback(userId, conversationId, input.message());

        ArrayNode messages = conversationMessages(conversationId);
        appendRequestIdHint(messages, input.message());
        List<AssistantModels.ToolExecution> executions = new ArrayList<>();
        for (int round = 0; round < maxToolRounds; round++) {
            OpenAiCompatibleClient.ModelMessage response = model.complete(messages);
            if (response.toolCalls() == null || !response.toolCalls().isArray() || response.toolCalls().isEmpty()) {
                String content = response.content().isBlank() ? "请补充您需要的物资或服务信息。" : response.content();
                saveMessage(conversationId, "ASSISTANT", content, null);
                return new AssistantModels.ChatResponse(conversationId, content, executions, null, true);
            }
            ObjectNode assistant = json.createObjectNode().put("role", "assistant");
            if (response.content().isBlank()) assistant.putNull("content"); else assistant.put("content", response.content());
            assistant.set("tool_calls", response.toolCalls());
            messages.add(assistant);

            for (JsonNode call : response.toolCalls()) {
                String callId = call.path("id").asText();
                String name = call.path("function").path("name").asText();
                JsonNode arguments = parseArguments(call.path("function").path("arguments").asText("{}"));
                AssistantToolExecutor.ToolResult result;
                try {
                    result = tools.execute(userId, name, arguments);
                } catch (RuntimeException failure) {
                    // A bad argument from the model must not abort the whole turn. Hand the
                    // failure back as a tool result so the model can correct itself.
                    Object error = Map.of("error", describe(failure));
                    executions.add(new AssistantModels.ToolExecution(name, error));
                    String errorJson = serialize(error);
                    saveMessage(conversationId, "TOOL", errorJson, name);
                    messages.add(json.createObjectNode().put("role", "tool").put("tool_call_id", callId)
                        .put("name", name).put("content", errorJson));
                    continue;
                }
                if (result.confirmation() != null) {
                    String content = "执行前请确认：" + result.confirmation().summary();
                    saveMessage(conversationId, "ASSISTANT", content, name);
                    return new AssistantModels.ChatResponse(conversationId, content, executions,
                        result.confirmation(), true);
                }
                executions.add(new AssistantModels.ToolExecution(name, result.result()));
                String resultJson = serialize(result.result());
                saveMessage(conversationId, "TOOL", resultJson, name);
                messages.add(json.createObjectNode().put("role", "tool").put("tool_call_id", callId)
                    .put("name", name).put("content", resultJson));
            }
        }
        String content = "为了安全，工具调用次数已达到上限。请缩小问题范围后重试。";
        saveMessage(conversationId, "ASSISTANT", content, null);
        return new AssistantModels.ChatResponse(conversationId, content, executions, null, true);
    }

    private static final String FLOW = """
        使用流程：
        ① 在“求助”页从现有物资中选择所需物资和数量，设置位置，并选择“到点自取”或“补给车配送”；
        ② 系统立即自动审批：库存足够就批准，锁定离你最近且有货的服务点的物资，并发放预约编号和 6 位领取码（保留 48 小时）；库存不足会说明原因；
        ③ 到点自取：在营业时间内到该服务点出示领取码；补给车配送：系统自动派最近的补给车先取货再送达，可在“进度”页查看车辆位置和预计到达时间；
        ④ 库存中没有的物资可选“其他需求”，会转人工处理。完成后可在“进度”页提交服务反馈。所有物资均为公益免费。""";
    private static final String HELP = "我可以直接查询：①“现在有哪些物资”“有大米吗” ②“服务点在哪、几点开门” "
        + "③“我的申请进度” ④“我的预约和领取码” ⑤“怎么申请和领取”。所有物资均为公益免费。";
    // Longest first so that "大米" wins over "米" and "饮用水" over "水".
    private static final List<String> ITEM_TERMS = List.of("婴幼儿", "饮用水", "食用油", "卫生用品", "急救", "医疗",
        "大米", "挂面", "抽纸", "手电", "电池", "应急", "食品", "护理", "米", "面", "油", "水", "纸");
    private static final Map<String, String> CATEGORY_TERMS = Map.ofEntries(
        Map.entry("吃", "FOOD"), Map.entry("粮", "FOOD"), Map.entry("饭", "FOOD"), Map.entry("食", "FOOD"),
        Map.entry("喝", "WATER"), Map.entry("卫生", "HYGIENE"), Map.entry("洗", "HYGIENE"),
        Map.entry("尿", "HYGIENE"), Map.entry("药", "MEDICAL"), Map.entry("医", "MEDICAL"),
        Map.entry("照明", "OTHER"));

    private AssistantModels.ChatResponse fallback(long userId, String conversationId, String message) {
        List<AssistantModels.ToolExecution> executions = new ArrayList<>();
        String content;
        if (containsAny(message, "危险", "昏迷", "晕倒", "流血", "火灾", "报警", "救命", "需要急救", "120", "110")) {
            content = "如有人身危险或紧急医疗情况，请立即拨打 120；涉及治安或人身安全请拨打 110。这里可以继续帮您查询青浦公益物资和服务点，但不能代替紧急救援。";
        } else if (containsAny(message, "领取码", "预约记录", "我的预约", "预约了什么", "预约状态")) {
            content = describeReservations(run(userId, "list_my_reservations", json.createObjectNode(), executions));
        } else if (containsAny(message, "怎么", "如何", "流程", "步骤", "怎样")
            && containsAny(message, "申请", "预约", "领", "提交", "配送", "送", "求助", "用")) {
            content = FLOW;
        } else if (containsAny(message, "申请", "进度", "状态", "单子", "订单", "工单", "request")) {
            String requestId = extractRequestId(message);
            if (requestId != null) {
                ObjectNode args = json.createObjectNode().put("requestId", Long.parseLong(requestId));
                try {
                    AssistantToolExecutor.ToolResult result = tools.execute(userId, "get_request_status", args);
                    executions.add(new AssistantModels.ToolExecution("get_request_status", result.result()));
                    content = describeRequest(result.result());
                } catch (IllegalArgumentException notFound) {
                    content = "未找到属于您的申请 #" + requestId + "，请检查编号后重试。";
                }
            } else {
                content = describeRequests(run(userId, "list_my_requests", json.createObjectNode(), executions));
            }
        } else if (containsAny(message, "库存", "物资", "有什么", "可领", "领什么", "inventory")
            || matchedItemTerm(message) != null || matchedCategory(message) != null) {
            String term = matchedItemTerm(message);
            String category = term == null ? matchedCategory(message) : null;
            ObjectNode args = json.createObjectNode();
            if (category != null) args.put("category", category);
            Object result = run(userId, "check_inventory", args, executions);
            content = term == null ? describeInventory(result) : describeInventory(result, term);
        } else if (containsAny(message, "服务点", "青浦", "领取点", "在哪", "地址", "几点", "营业", "开门", "关门",
            "时间", "附近", "位置")) {
            content = describeServicePoints(run(userId, "list_service_points", json.createObjectNode(), executions));
        } else {
            content = HELP;
        }
        saveMessage(conversationId, "ASSISTANT", content, null);
        return new AssistantModels.ChatResponse(conversationId, content, executions, null, false);
    }

    private Object run(long userId, String tool, JsonNode args, List<AssistantModels.ToolExecution> executions) {
        AssistantToolExecutor.ToolResult result = tools.execute(userId, tool, args);
        executions.add(new AssistantModels.ToolExecution(tool, result.result()));
        return result.result();
    }

    static String matchedItemTerm(String message) {
        return ITEM_TERMS.stream().filter(message::contains).findFirst().orElse(null);
    }

    static String matchedCategory(String message) {
        return CATEGORY_TERMS.entrySet().stream().filter(entry -> message.contains(entry.getKey()))
            .map(Map.Entry::getValue).findFirst().orElse(null);
    }

    /** Answers a question about one kind of item, e.g. "有大米吗", listing every point that stocks it. */
    static String describeInventory(Object value, String term) {
        List<InventoryItemView> matches = value instanceof List<?> values ? values.stream()
            .filter(InventoryItemView.class::isInstance).map(InventoryItemView.class::cast)
            .filter(item -> item.name().contains(term)).toList() : List.of();
        if (matches.isEmpty()) {
            return "目前没有名称包含“" + term + "”的物资。您可以问“现在有哪些物资”查看全部，"
                + "或在“求助”页提交具体需求，我们会记录并跟进。";
        }
        return describeInventory(matches);
    }

    static String describeInventory(Object value) {
        if (!(value instanceof List<?> values) || values.isEmpty()) {
            return "当前没有可领取库存；您可以在首页提交具体需求，我们会记录并跟进。";
        }
        List<InventoryItemView> available = values.stream()
            .filter(InventoryItemView.class::isInstance).map(InventoryItemView.class::cast)
            .filter(item -> item.freeQuantity() > 0).toList();
        if (available.isEmpty()) {
            return "当前库存已全部预约完；您可以在首页提交具体需求，我们会记录并跟进。";
        }
        StringBuilder answer = new StringBuilder("当前可免费领取：\n");
        available.stream().limit(20).forEach(item -> answer.append("• ").append(item.name())
            .append("：").append(item.freeQuantity()).append(item.unit())
            .append("（").append(item.servicePointName()).append("）\n"));
        answer.append("请先提交需求；审核通过后，到“物资预约”选择对应物资和数量。");
        return answer.toString();
    }

    static String describeRequest(Object value) {
        if (!(value instanceof SupplyRequestView request)) return "申请信息暂时无法识别，请稍后重试。";
        String status = switch (request.status()) {
            case SUBMITTED -> "已提交，等待审核";
            case UNDER_REVIEW -> "审核中";
            case APPROVED -> "已批准，可以预约物资";
            case SCHEDULED -> "已安排，等待领取或配送";
            case FULFILLED -> "已完成";
            case REJECTED -> "未通过";
            case CANCELLED -> "已取消";
        };
        StringBuilder answer = new StringBuilder("申请 #" + request.id() + "：" + request.itemDescription() + "，数量 "
            + request.quantity() + "，当前状态：" + status + "。");
        if (request.decisionNote() != null) answer.append(request.decisionNote());
        if (request.fulfillmentMethod() != null) {
            answer.append("领取方式：").append(request.fulfillmentMethod() == FulfillmentMethod.PICKUP ? "到点自取" : "补给车配送");
            if (request.assignedServicePointName() != null) answer.append("（").append(request.assignedServicePointName()).append("）");
            if (request.assignedCartName() != null) answer.append("（").append(request.assignedCartName()).append("）");
            answer.append("。");
        }
        return answer.toString();
    }

    static String describeRequests(Object value) {
        if (!(value instanceof List<?> rows) || rows.isEmpty()) {
            return "您还没有提交过申请。可在“求助”页填写所需物资、数量和位置。";
        }
        StringBuilder answer = new StringBuilder("您最近的申请：\n");
        rows.stream().filter(SupplyRequestView.class::isInstance).map(SupplyRequestView.class::cast)
            .forEach(request -> answer.append("• ").append(describeRequest(request)).append("\n"));
        answer.append("可在“进度”页查看详情；已批准的申请可以预约物资。");
        return answer.toString();
    }

    static String describeReservations(Object value) {
        if (!(value instanceof List<?> rows) || rows.isEmpty()) {
            return "您还没有物资预约。申请获批后，可在“进度”页点“预约物资”获得领取码。";
        }
        DateTimeFormatter format = DateTimeFormatter.ofPattern("M月d日 HH:mm").withZone(ZoneId.of("Asia/Shanghai"));
        StringBuilder answer = new StringBuilder("您最近的预约：\n");
        rows.stream().filter(ReservationService.ReservationView.class::isInstance)
            .map(ReservationService.ReservationView.class::cast)
            .forEach(reservation -> {
                answer.append("• 预约 #").append(reservation.reservationId()).append("：").append(reservation.itemName())
                    .append(" × ").append(reservation.quantity()).append("，").append(reservationStatus(reservation.status()));
                if (reservation.pickupCode() != null && ("HELD".equals(reservation.status())
                    || "CONFIRMED".equals(reservation.status()))) {
                    answer.append("，领取码 ").append(reservation.pickupCode()).append("，有效期至 ")
                        .append(format.format(reservation.expiresAt())).append("，物资来自 ")
                        .append(reservation.servicePointName());
                }
                answer.append("\n");
            });
        answer.append("领取或配送送达时，请出示预约编号和领取码。");
        return answer.toString();
    }

    private static String reservationStatus(String status) {
        return switch (status) {
            case "HELD", "CONFIRMED" -> "待领取";
            case "COLLECTED" -> "已领取";
            case "EXPIRED" -> "已过期";
            case "CANCELLED" -> "已取消";
            default -> status;
        };
    }

    static String describeServicePoints(Object value) {
        if (!(value instanceof List<?> points) || points.isEmpty()) {
            return "当前没有匹配的服务点，请改用“青浦有哪些服务点”查询全部青浦点位。";
        }
        StringBuilder answer = new StringBuilder("当前公益服务点：\n");
        points.stream().filter(Map.class::isInstance).map(Map.class::cast).limit(10)
            .forEach(point -> {
                answer.append("• ").append(point.get("name")).append("：").append(point.get("address"));
                if (point.get("opensAt") != null && point.get("closesAt") != null) {
                    answer.append("，营业时间 ").append(point.get("opensAt")).append("–").append(point.get("closesAt"));
                }
                answer.append("\n");
            });
        answer.append("可在首页地图查看位置并导航；选择“到点自取”时请在营业时间内前往。");
        return answer.toString();
    }

    private String ensureConversation(long userId, String requestedId) {
        if (requestedId != null && requestedId.matches("[0-9a-fA-F-]{36}")) {
            int owned = jdbc.sql("SELECT COUNT(*) FROM assistant_conversations WHERE id=:id AND user_id=:userId")
                .param("id", requestedId).param("userId", userId).query(Integer.class).single();
            if (owned > 0) return requestedId;
        }
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO assistant_conversations (id, user_id) VALUES (:id, :userId)")
            .param("id", id).param("userId", userId).update();
        return id;
    }

    private ArrayNode conversationMessages(String conversationId) {
        ArrayNode messages = json.createArrayNode();
        messages.add(json.createObjectNode().put("role", "system").put("content", SYSTEM_PROMPT));
        jdbc.sql("""
                SELECT role, content, tool_name FROM assistant_messages
                WHERE conversation_id=:id AND role IN ('USER','ASSISTANT') ORDER BY id DESC LIMIT 12
                """).param("id", conversationId)
            .query((rs, n) -> new StoredMessage(rs.getString("role"), rs.getString("content"))).list()
            .reversed().forEach(message -> messages.add(json.createObjectNode()
                .put("role", message.role().toLowerCase()).put("content", message.content())));
        return messages;
    }

    private void saveMessage(String conversationId, String role, String content, String toolName) {
        jdbc.sql("""
                INSERT INTO assistant_messages (conversation_id, role, content, tool_name)
                VALUES (:conversationId, :role, :content, :toolName)
                """).param("conversationId", conversationId).param("role", role)
            .param("content", content).param("toolName", toolName).update();
    }

    private JsonNode parseArguments(String value) {
        try {
            return json.readTree(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Model returned invalid tool arguments", e);
        }
    }

    // A small model reliably misses colloquial ids such as "17号单子", and no amount of
    // prompting fixes that. Extracting the id deterministically and stating it turns an
    // inference the model is bad at into a fact it only has to use.
    private void appendRequestIdHint(ArrayNode messages, String message) {
        String requestId = extractRequestId(message);
        if (requestId == null) return;
        messages.add(json.createObjectNode().put("role", "system")
            .put("content", "用户本次消息中提到的申请编号是 " + requestId
                + "，查询申请状态时以此编号调用 get_request_status。"));
    }

    private static String extractRequestId(String message) {
        Matcher matcher = REQUEST_ID.matcher(message);
        if (!matcher.find()) return null;
        return matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
    }

    private String describe(RuntimeException failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message;
    }

    private String serialize(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Tool result cannot be serialized", e);
        }
    }

    private boolean containsAny(String value, String... options) {
        String normalized = value.toLowerCase();
        for (String option : options) if (normalized.contains(option.toLowerCase())) return true;
        return false;
    }

    record StoredMessage(String role, String content) {
    }
}
