package org.isolatedareas.helphub.assistant;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

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
        用户给出申请编号时必须调用 get_request_status；未给出编号时先向用户索要编号。
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
            String content = "操作已确认并完成。";
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

    private AssistantModels.ChatResponse fallback(long userId, String conversationId, String message) {
        List<AssistantModels.ToolExecution> executions = new ArrayList<>();
        String content;
        if (containsAny(message, "库存", "物资", "inventory")) {
            JsonNode args = json.createObjectNode();
            AssistantToolExecutor.ToolResult result = tools.execute(userId, "check_inventory", args);
            executions.add(new AssistantModels.ToolExecution("check_inventory", result.result()));
            content = "AI 服务尚未配置，已直接返回实时库存。";
        } else if (containsAny(message, "状态", "申请", "request")) {
            String requestId = extractRequestId(message);
            if (requestId != null) {
                ObjectNode args = json.createObjectNode().put("requestId", Long.parseLong(requestId));
                AssistantToolExecutor.ToolResult result = tools.execute(userId, "get_request_status", args);
                executions.add(new AssistantModels.ToolExecution("get_request_status", result.result()));
                content = "AI 服务尚未配置，已直接查询该申请。";
            } else {
                content = "请提供申请编号，例如“查询申请 123”。";
            }
        } else {
            content = "AI 服务尚未配置。您仍可使用需求提交、库存预约、附近服务点和申请查询功能。";
        }
        saveMessage(conversationId, "ASSISTANT", content, null);
        return new AssistantModels.ChatResponse(conversationId, content, executions, null, false);
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
