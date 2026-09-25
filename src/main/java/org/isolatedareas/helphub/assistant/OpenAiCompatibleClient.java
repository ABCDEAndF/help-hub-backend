package org.isolatedareas.helphub.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class OpenAiCompatibleClient {
    private static final java.util.List<String> URGENCY_LEVELS =
        java.util.Arrays.stream(org.isolatedareas.helphub.domain.Urgency.values()).map(Enum::name).toList();

    private final String apiKey;
    private final String model;
    private final RestClient client;
    private final ObjectMapper json;

    @Autowired
    public OpenAiCompatibleClient(@Value("${app.ai.base-url}") String baseUrl,
                                  @Value("${app.ai.api-key}") String apiKey,
                                  @Value("${app.ai.model}") String model,
                                  RestClient.Builder builder, ObjectMapper json) {
        this(apiKey, model, configuredClient(baseUrl, builder), json);
    }

    OpenAiCompatibleClient(String apiKey, String model, RestClient client, ObjectMapper json) {
        this.apiKey = apiKey;
        this.model = model;
        this.client = client;
        this.json = json;
    }

    private static RestClient configuredClient(String baseUrl, RestClient.Builder builder) {
        SimpleClientHttpRequestFactory requests = new SimpleClientHttpRequestFactory();
        requests.setConnectTimeout(java.time.Duration.ofSeconds(5));
        requests.setReadTimeout(java.time.Duration.ofSeconds(30));
        return builder.requestFactory(requests).baseUrl(baseUrl).build();
    }

    public boolean configured() {
        return apiKey != null && !apiKey.isBlank();
    }

    public ModelMessage complete(ArrayNode messages) {
        ObjectNode body = json.createObjectNode();
        body.put("model", model);
        body.put("temperature", 0.1);
        body.set("messages", messages);
        body.set("tools", tools());
        body.put("tool_choice", "auto");
        JsonNode response = client.post().uri("/chat/completions")
            .contentType(MediaType.APPLICATION_JSON)
            .header("Authorization", "Bearer " + apiKey)
            .body(body).retrieve().body(JsonNode.class);
        JsonNode message = response == null ? null : response.path("choices").path(0).path("message");
        if (message == null || message.isMissingNode()) throw new IllegalStateException("LLM returned no message");
        return new ModelMessage(message.path("content").isNull() ? "" : message.path("content").asText(),
            message.path("tool_calls"));
    }

    private ArrayNode tools() {
        ArrayNode tools = json.createArrayNode();
        tools.add(tool("check_inventory", "查询当前可用物资库存。省略 category 表示查询全部。", objectSchema()
            .set("properties", json.createObjectNode().set("category",
                enumProperty("可选物资类别；查询全部时不要传该字段", AssistantToolExecutor.INVENTORY_CATEGORIES)))));
        tools.add(tool("get_request_status",
            "查询当前登录居民本人的申请状态。后端已完成归属校验，你有权直接调用，无需额外授权。",
            schema(json.createObjectNode().set("requestId", integerProperty("申请编号")), "requestId")));
        ObjectNode geoProperties = json.createObjectNode();
        geoProperties.set("latitude", numberProperty("纬度"));
        geoProperties.set("longitude", numberProperty("经度"));
        geoProperties.set("radiusMeters", integerProperty("搜索半径，默认5000米"));
        tools.add(tool("list_service_points",
            "按名称或地名（如“浦东”“闵行”）列出服务点，不需要坐标。"
                + "用户用地名提问、或没有提供经纬度时使用本工具；省略 keyword 表示列出全部。",
            objectSchema().set("properties", json.createObjectNode()
                .set("keyword", stringProperty("服务点名称或所在地区关键字，可省略")))));
        tools.add(tool("find_nearby_service_points",
            "查找居民附近的服务点。latitude 与 longitude 必须来自用户明确给出的数值，"
                + "绝不可根据地名推测或估算；用户只说了地名而没给坐标时，不要调用本工具，改为向其索要位置。",
            schema(geoProperties, "latitude", "longitude")));
        ObjectNode requestProperties = json.createObjectNode();
        requestProperties.set("category", enumProperty("物资类别", AssistantToolExecutor.INVENTORY_CATEGORIES));
        requestProperties.set("itemDescription", stringProperty("所需物资"));
        requestProperties.set("quantity", integerProperty("数量"));
        requestProperties.set("urgency", enumProperty("紧急程度", URGENCY_LEVELS));
        requestProperties.set("latitude", numberProperty("纬度"));
        requestProperties.set("longitude", numberProperty("经度"));
        requestProperties.set("approximateAddress", stringProperty("大致地址"));
        requestProperties.set("accessibilityNotes", stringProperty("行动不便等说明"));
        tools.add(tool("prepare_supply_request",
            "提交物资需求。调用本工具只会生成一张确认卡片交给用户，不会立即写入数据；"
                + "参数齐全时请直接调用，不要先用文字向用户征求同意。",
            schema(requestProperties, "category", "itemDescription", "quantity", "urgency",
                "latitude", "longitude")));
        ObjectNode reserveProperties = json.createObjectNode();
        reserveProperties.set("requestId", integerProperty("已批准的申请编号"));
        reserveProperties.set("inventoryItemId", integerProperty("库存编号"));
        reserveProperties.set("quantity", integerProperty("预约数量"));
        tools.add(tool("reserve_pickup_slot",
            "预约领取物资。调用本工具只会生成一张确认卡片交给用户，不会立即写入数据；"
                + "参数齐全时请直接调用，不要先用文字向用户征求同意。",
            schema(reserveProperties, "requestId", "inventoryItemId", "quantity")));
        return tools;
    }

    private ObjectNode tool(String name, String description, JsonNode parameters) {
        ObjectNode function = json.createObjectNode().put("name", name).put("description", description);
        function.set("parameters", parameters);
        return json.createObjectNode().put("type", "function").set("function", function);
    }
    private ObjectNode objectSchema() { return json.createObjectNode().put("type", "object").put("additionalProperties", false); }
    private ObjectNode schema(ObjectNode properties, String... required) {
        ObjectNode schema = objectSchema();
        schema.set("properties", properties);
        ArrayNode requiredFields = json.createArrayNode();
        for (String field : required) requiredFields.add(field);
        if (!requiredFields.isEmpty()) schema.set("required", requiredFields);
        return schema;
    }
    private ObjectNode stringProperty(String description) { return json.createObjectNode().put("type", "string").put("description", description); }
    private ObjectNode enumProperty(String description, java.util.List<String> values) {
        ObjectNode property = stringProperty(description);
        ArrayNode allowed = json.createArrayNode();
        values.forEach(allowed::add);
        property.set("enum", allowed);
        return property;
    }
    private ObjectNode numberProperty(String description) { return json.createObjectNode().put("type", "number").put("description", description); }
    private ObjectNode integerProperty(String description) { return json.createObjectNode().put("type", "integer").put("description", description); }

    public record ModelMessage(String content, JsonNode toolCalls) {
    }
}
