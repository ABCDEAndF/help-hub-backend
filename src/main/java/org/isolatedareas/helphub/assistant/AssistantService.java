package org.isolatedareas.helphub.assistant;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.isolatedareas.helphub.automation.CartTripService;
import org.isolatedareas.helphub.domain.FulfillmentMethod;
import org.isolatedareas.helphub.domain.RequestStatus;
import org.isolatedareas.helphub.inventory.InventoryItemView;
import org.isolatedareas.helphub.inventory.ReservationService;
import org.isolatedareas.helphub.requests.SupplyRequestView;

/**
 * The service assistant: everything a resident can do in the mini program, done by talking to it.
 * A model reads every message, in whatever words and language it comes, and picks what it asks for.
 * Facts (stock, service points and their hours, the resident's own requests, codes, notices, deliveries)
 * are then written from live data rather than by the model, which was seen to guess opening hours and
 * list the wrong items. Changes (requests, cancellations, feedback, profile, location) are described
 * first and made only once the resident agrees in the chat ("确认"). What only the phone can do (save
 * the place, switch language, dial, open navigation or a page) is sent to the client to do at once.
 * Only real danger is answered without the model, and the keyword lookup answers when no model is available.
 */
@Service
public class AssistantService {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AssistantService.class);
    private static final Pattern REQUEST_ID = Pattern.compile(
        "(?:申请|单子|订单|工单|request|#)\\s*#?\\s*(\\d+)|(\\d+)\\s*号?\\s*(?:申请|单子|订单|工单)",
        Pattern.CASE_INSENSITIVE);
    /** Tools that only read; what they find is written from live data, not by the model. */
    static final Set<String> READ_TOOLS = Set.of("check_inventory", "list_service_points", "find_nearby_service_points",
        "list_my_requests", "get_request_status", "list_my_reservations", "list_my_notifications", "get_my_profile",
        "track_delivery", "explain_how");
    /**
     * How to do each thing in the app, step by step. Fixed text, because a model asked to describe the
     * pages was seen to send residents to the wrong one. Each ends by offering to do it in the chat.
     */
    static final Map<String, String> GUIDES = Map.ofEntries(
        Map.entry("set_location", "设置位置：在首页“当前位置”右侧点“设置”（或“修改”），也可以在地图页点“设置我的位置”。填写详细地址（到门牌号和室号），"
            + "点“查找位置”，从结果里选出您家，最后点“确认使用此位置”。不需要开启手机定位。也可以直接把地址告诉我，我来帮您设置。"),
        Map.entry("apply", "申请物资：在“求助”页选择需要的物资和数量（库存里没有的选“其他”并写明），选择紧急程度、领取方式（到点自取或补给车配送）和时间，"
            + "确认位置后点“确认提交”。也可以直接告诉我要什么、要多少，我来帮您申请。"),
        Map.entry("cancel", "取消申请：在“进度”页找到还在等待审核的申请，点“取消申请”。已经批准或已完成的申请不能自己取消，请拨打社区服务热线。"
            + "也可以直接告诉我要取消哪个申请。"),
        Map.entry("pickup_code", "查看领取码：在“我的”页点“我的领取码”。领取码保留 48 小时，到点自取或配送送达时出示。也可以直接问我“我的领取码”。"),
        Map.entry("status", "查看申请进度：打开“进度”页，下拉可以刷新。也可以直接问我“我的申请进度”。"),
        Map.entry("track", "查看配送：在“进度”页点申请下方的“查看配送位置”，或打开“地图”页看补给车的位置和预计到达时间。也可以直接问我“补给车到哪了”。"),
        Map.entry("feedback", "服务评价：申请完成后，在“进度”页点“服务反馈”，打 1 到 5 星，可以填以前和这次各花了多少分钟，以及意见。每个申请只能评价一次。"
            + "也可以直接告诉我打几星，我来帮您提交。"),
        Map.entry("profile", "修改资料：在“我的”页点上方的资料卡，可以修改称呼和家庭人数。也可以直接告诉我要改成什么。"),
        Map.entry("language", "切换语言：打开小程序时首页会请您选择中文或英文（勾选过“设为默认语言”就不会再问）。也可以直接告诉我“切换成英文”或“切换成中文”。"),
        Map.entry("hotline", "社区服务热线：18321961090。在“我的”页点“社区服务热线”可以直接拨打，也可以让我帮您拨打。"),
        Map.entry("notifications", "查看通知：在“我的”页点“进度通知”。也可以直接问我“我有什么通知”。"),
        Map.entry("reserve", "预约物资：工作人员已批准、但还没有领取码的申请，在“进度”页点“预约物资”，选好同类物资和数量后生成领取码（保留 48 小时）。"
            + "也可以直接告诉我为哪个申请预约。"),
        Map.entry("navigate", "导航到服务点：在首页“附近服务”里点一个服务点，或在“地图”页选中服务点后点“在微信地图中导航”。"
            + "也可以直接告诉我去哪个服务点，我帮您打开导航。"));
    /** Tools that change something; each is described to the resident and run once they agree. */
    static final Set<String> ACTION_TOOLS = Set.of("prepare_supply_request", "cancel_my_request", "reserve_pickup_slot",
        "submit_feedback", "update_my_profile");
    /** Tools this service answers itself: address search, the phone's own actions, the waiting action. */
    static final Set<String> DIRECT_TOOLS = Set.of("search_address", "choose_address", "set_language", "call_hotline",
        "open_navigation", "open_page", "confirm_pending_action", "cancel_pending_action");
    /** Tools that act on the resident's saved place, which the server supplies so no model can invent one. */
    private static final Set<String> PLACE_TOOLS = Set.of("prepare_supply_request");
    private static final int NEARBY_RADIUS_METERS = 10_000;
    static final String DECLINED = "好的，已放弃这项操作，没有做任何修改。";
    private static final Map<String, String[]> PAGES = Map.of(
        "home", new String[] {"/pages/home/index", "首页"}, "map", new String[] {"/pages/map/index", "地图"},
        "request", new String[] {"/pages/request-create/index", "求助"}, "status", new String[] {"/pages/requests/index", "进度"},
        "me", new String[] {"/pages/profile/index", "我的"}, "pickup_codes", new String[] {"/pages/reservations/index", "我的领取码"},
        "notifications", new String[] {"/pages/notifications/index", "服务通知"},
        "set_location", new String[] {"/pages/place/index", "填写我的位置"},
        "feedback", new String[] {"/pages/feedback/index", "服务反馈"}, "track", new String[] {"/pages/map/index", "地图"});

    private final OpenAiCompatibleClient model;
    private final AssistantToolExecutor tools;
    private final ConfirmationService confirmations;
    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final int maxToolRounds;
    private final String hotline;
    /** How the model is to read a message into intents (see the resource, which the evaluation script reads too). */
    private final String understandingPrompt;

    public AssistantService(OpenAiCompatibleClient model, AssistantToolExecutor tools, ConfirmationService confirmations,
                            JdbcClient jdbc, ObjectMapper json, @Value("${app.ai.max-tool-rounds}") int maxToolRounds,
                            @Value("${app.service-phone:18321961090}") String hotline) {
        this.model = model;
        this.tools = tools;
        this.confirmations = confirmations;
        this.jdbc = jdbc;
        this.json = json;
        this.maxToolRounds = maxToolRounds;
        this.hotline = hotline;
        this.understandingPrompt = resource("/assistant/understanding-prompt.txt");
    }

    private static String resource(String path) {
        try (java.io.InputStream in = AssistantService.class.getResourceAsStream(path)) {
            if (in == null) throw new IllegalStateException("Missing " + path);
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException unreadable) {
            throw new IllegalStateException("Cannot read " + path, unreadable);
        }
    }

    public AssistantModels.ChatResponse chat(long userId, AssistantModels.ChatRequest input) {
        String conversationId = ensureConversation(userId, input.conversationId());
        String language = replyLanguage(input.message(), input.language());
        saveMessage(conversationId, "USER", input.message(), null);
        // Earlier mini program versions agree on a card that sends its token.
        if (input.confirmationToken() != null && !input.confirmationToken().isBlank()) {
            confirmations.stopWaiting(userId, conversationId);
            return confirmed(userId, conversationId, input.confirmationToken(), language);
        }

        String waiting = confirmations.waiting(userId, conversationId);
        ConfirmationService.PendingAction pending = waiting == null ? null : tools.pending(userId, waiting);
        if (waiting != null && pending == null) {
            confirmations.stopWaiting(userId, conversationId);
            waiting = null;
        }
        // Someone in danger is told whom to call at once, whatever a model would make of it.
        if (inDanger(input.message())) {
            dropWaiting(userId, conversationId, waiting);
            return reply(conversationId, EMERGENCY, Turn.empty(), language, false);
        }
        // A plain yes or no to the action waiting for the resident needs no model.
        if (pending != null) {
            Answer answer = answerTo(input.message(), pending.tool());
            if (answer == Answer.YES) {
                confirmations.stopWaiting(userId, conversationId);
                return confirmed(userId, conversationId, waiting, language);
            }
            if (answer == Answer.NO) {
                dropWaiting(userId, conversationId, waiting);
                return reply(conversationId, DECLINED, Turn.empty(), language, false);
            }
        }
        String lookupText = lookupText(input);
        JsonNode understood = null;
        if (model.configured()) {
            try {
                understood = understand(userId, conversationId, input, language, pending);
            } catch (RuntimeException unavailable) {
                // Quota used up, key revoked, timeout: answer from live data without the model
                // instead of failing the whole question.
                log.warn("LLM unavailable, answering without it: {}", unavailable.getMessage());
            }
        }
        if (understood == null) {
            dropWaiting(userId, conversationId, waiting);
            return fallback(userId, conversationId, lookupText, language);
        }
        return act(userId, conversationId, input, language, waiting, pending, understood);
    }

    /**
     * What the resident wants, read by the model as {"actions":[{"intent":...}], "reply":...}; asked once
     * more when the answer is not JSON or claims something was done that no intent does. Null when it
     * still cannot be read.
     */
    private JsonNode understand(long userId, String conversationId, AssistantModels.ChatRequest input, String language,
                                ConfirmationService.PendingAction pending) {
        ArrayNode messages = json.createArrayNode();
        system(messages, understandingPrompt);
        system(messages, context(userId, input, pending));
        String number = extractRequestId(input.message());
        if (number != null) system(messages, "居民这句话里提到的申请编号是 " + number + "。");
        String earlier = transcript(conversationId, input.message());
        if (!earlier.isEmpty()) system(messages, "前面的对话（供参考，不要模仿助手的话）：\n" + earlier);
        messages.add(json.createObjectNode().put("role", "user").put("content", input.message()));
        system(messages, replyRule(language));
        String raw = model.completeJson(messages);
        JsonNode understood = parseUnderstanding(raw);
        String problem = problemWith(understood, input.message());
        if (problem == null) return understood;
        log.info("Asking the model again ({}): {}", problem, raw);
        // An empty message is refused by the provider (400), so an empty answer is not echoed back.
        if (raw != null && !raw.isBlank()) messages.add(json.createObjectNode().put("role", "assistant").put("content", raw));
        system(messages, problem);
        understood = parseUnderstanding(model.completeJson(messages));
        return problemWith(understood, input.message()) == null ? understood : null;
    }

    /** Does what the resident asked, in order, and replies. */
    private AssistantModels.ChatResponse act(long userId, String conversationId, AssistantModels.ChatRequest input,
                                             String language, String waiting, ConfirmationService.PendingAction pending,
                                             JsonNode understood) {
        Turn turn = new Turn();
        List<String> problems = new ArrayList<>();
        String chat = null;
        JsonNode actions = understood.path("actions");
        String replyText = understood.path("reply").asText("").trim();
        if (!actions.isArray() || actions.isEmpty()) chat = replyText;
        for (JsonNode action : actions.isArray() ? actions : json.createArrayNode()) {
            String intent = action.path("intent").asText("").trim().toLowerCase();
            if (intent.equals("confirm") || intent.equals("decline")) {
                if (pending == null) continue;
                if (intent.equals("confirm")) {
                    confirmations.stopWaiting(userId, conversationId);
                    return confirmed(userId, conversationId, waiting, language);
                }
                dropWaiting(userId, conversationId, waiting);
                return reply(conversationId, DECLINED, Turn.empty(), language, true);
            }
            Step step = withoutInventedName(step(action, json), input.message());
            if (step == null) {
                chat = replyText;
                continue;
            }
            try {
                String part;
                if (READ_TOOLS.contains(step.tool())) part = liveAnswer(userId, step.tool(), step.args(), input, turn.executions);
                else if (DIRECT_TOOLS.contains(step.tool())) part = direct(userId, conversationId, step.tool(), step.args(), input, turn);
                else part = prepare(userId, step.tool(), step.args(), input, turn);
                if (part != null && !part.isBlank() && !turn.parts.contains(part)) turn.parts.add(part);
            } catch (ResidentQuestion question) {
                // A choice the service words itself: shown as it is (and translated by the client).
                if (!turn.parts.contains(question.getMessage())) turn.parts.add(question.getMessage());
            } catch (RuntimeException failure) {
                log.info("{} refused: {}", step.tool(), failure.getMessage());
                problems.add(describe(failure));
            }
        }
        if (turn.confirmation != null) {
            if (waiting != null && !waiting.equals(turn.confirmation.token())) confirmations.discard(userId, waiting);
            confirmations.await(userId, conversationId, turn.confirmation.token());
            turn.parts.add("请确认：" + turn.confirmation.summary() + "。回复“确认”就会执行，回复“不要”则放弃。");
        } else {
            // The resident moved on without agreeing: the waiting action lapses.
            dropWaiting(userId, conversationId, waiting);
        }
        // What the model itself says: a chat answer, or why something could not be done and what to do next.
        String said = null;
        if (!problems.isEmpty()) said = phrase(input.message(), problems, language);
        else if (turn.parts.isEmpty() && chat != null && !chat.isBlank()) said = inLanguage(plainText(chat), language);
        if (said != null) turn.parts.add(said);
        if (turn.parts.isEmpty()) return reply(conversationId, "请补充您需要的物资或服务信息。", turn, language, true);
        String content = String.join("\n\n", turn.parts);
        if (said != null && turn.parts.size() == 1) {
            // Only the model's own words: shown as they are, already in the resident's language.
            saveMessage(conversationId, "ASSISTANT", content, null);
            return new AssistantModels.ChatResponse(conversationId, content, turn.executions, null, true, language, true,
                turn.actions);
        }
        return reply(conversationId, content, turn, language, true);
    }

    /** One thing to do: the handler that does it and its arguments. */
    record Step(String tool, ObjectNode args) {
    }

    /**
     * The handler for one understood intent, with its arguments in the handler's terms; null for a chat
     * answer or an intent no handler knows.
     */
    static Step step(JsonNode action, ObjectMapper json) {
        String intent = action.path("intent").asText("").trim().toLowerCase();
        ObjectNode args = json.createObjectNode();
        String tool;
        switch (intent) {
            case "stock" -> {
                tool = "check_inventory";
                if (action.has("items")) args.set("items", action.get("items"));
                else if (action.has("item")) args.set("items", action.get("item"));
                copyText(action, "category", args, "category");
            }
            case "service_points" -> {
                tool = "list_service_points";
                copyText(action, "keyword", args, "keyword");
            }
            case "nearest_points" -> tool = "find_nearby_service_points";
            case "my_requests" -> tool = "list_my_requests";
            case "request_status" -> {
                tool = "get_request_status";
                copyNumber(action, "requestNumber", args, "requestId");
            }
            case "my_codes" -> tool = "list_my_reservations";
            case "my_notifications" -> tool = "list_my_notifications";
            case "my_profile" -> tool = "get_my_profile";
            case "track_delivery" -> {
                tool = "track_delivery";
                copyNumber(action, "requestNumber", args, "requestId");
            }
            case "how_to" -> {
                tool = "explain_how";
                args.put("topic", topic(action.path("topic").asText("")));
            }
            case "apply" -> {
                tool = "prepare_supply_request";
                copyText(action, "item", args, "itemDescription");
                copyNumber(action, "quantity", args, "quantity");
                copyText(action, "method", args, "fulfillmentMethod");
                copyText(action, "urgency", args, "urgency");
                copyText(action, "notes", args, "accessibilityNotes");
                copyText(action, "date", args, "date");
                copyText(action, "slot", args, "slot");
            }
            case "cancel_request" -> {
                tool = "cancel_my_request";
                copyNumber(action, "requestNumber", args, "requestId");
            }
            case "reserve" -> {
                tool = "reserve_pickup_slot";
                copyNumber(action, "requestNumber", args, "requestId");
                copyText(action, "item", args, "item");
                copyNumber(action, "quantity", args, "quantity");
            }
            case "feedback" -> {
                tool = "submit_feedback";
                copyNumber(action, "requestNumber", args, "requestId");
                copyNumber(action, "rating", args, "rating");
                copyNumber(action, "previousMinutes", args, "previousTravelMinutes");
                copyNumber(action, "currentMinutes", args, "currentTravelMinutes");
                copyText(action, "comments", args, "comments");
            }
            case "update_profile" -> {
                tool = "update_my_profile";
                copyText(action, "displayName", args, "displayName");
                copyNumber(action, "householdSize", args, "householdSize");
            }
            case "set_location" -> {
                tool = "search_address";
                copyText(action, "address", args, "address");
            }
            case "choose_place" -> {
                tool = "choose_address";
                copyNumber(action, "choice", args, "choice");
            }
            case "set_language" -> {
                tool = "set_language";
                copyText(action, "language", args, "language");
            }
            case "call_hotline" -> tool = "call_hotline";
            case "navigate" -> {
                tool = "open_navigation";
                copyText(action, "keyword", args, "keyword");
            }
            case "open_page" -> {
                tool = "open_page";
                copyText(action, "page", args, "page");
                copyNumber(action, "requestNumber", args, "requestId");
            }
            default -> {
                return null;
            }
        }
        return new Step(tool, args);
    }

    /**
     * A new name must be one the resident wrote: a small model was seen to take "张姐" from an example
     * when asked to "update my address". A name it made up is dropped, which leaves the model to ask.
     */
    static Step withoutInventedName(Step step, String message) {
        if (step == null || !"update_my_profile".equals(step.tool())) return step;
        String name = step.args().path("displayName").asText("");
        if (!name.isEmpty() && (message == null || !message.toLowerCase().contains(name.toLowerCase()))) {
            step.args().remove("displayName");
        }
        if (step.args().has("householdSize") && (message == null || !HOUSEHOLD.matcher(message).find())) {
            step.args().remove("householdSize");
        }
        return step;
    }

    // A household size said in the message: "4口人", "一家三口", "一个人住", "family of three", "I live alone".
    private static final Pattern HOUSEHOLD = Pattern.compile(
        "\\d|[一二两三四五六七八九十]+\\s*[口个人位]|\\b(one|two|three|four|five|six|seven|eight|nine|ten|alone|myself|just me)\\b",
        Pattern.CASE_INSENSITIVE);

    private static void copyText(JsonNode from, String field, ObjectNode to, String as) {
        String value = from.path(field).isValueNode() ? from.path(field).asText("").trim() : "";
        if (!value.isEmpty() && !value.equalsIgnoreCase("null")) to.put(as, value);
    }

    // Models write numbers as 2, "2", "#2" or "2号".
    private static void copyNumber(JsonNode from, String field, ObjectNode to, String as) {
        String digits = from.path(field).asText("").replaceAll("\\D", "");
        if (!digits.isEmpty() && digits.length() <= 9) to.put(as, Integer.parseInt(digits));
    }

    // The topic names the model was seen to use besides the listed ones.
    private static String topic(String value) {
        String topic = value.trim().toLowerCase().replace(' ', '_');
        return switch (topic) {
            case "cancel_request", "cancellation" -> "cancel";
            case "location", "place", "address", "change_location" -> "set_location";
            case "request", "apply_supplies", "application" -> "apply";
            case "pickup_codes", "code", "codes" -> "pickup_code";
            case "delivery", "tracking", "track_delivery" -> "track";
            case "rate", "rating" -> "feedback";
            case "notification", "messages" -> "notifications";
            case "navigation", "directions" -> "navigate";
            default -> topic;
        };
    }

    /**
     * The model's words for the resident when something could not be done or needs one more detail,
     * written from the service's own reasons; those reasons as they are when no model answers.
     */
    private String phrase(String message, List<String> problems, String language) {
        ArrayNode messages = json.createArrayNode();
        system(messages, "你是“邻需通”小程序的智能服务助手。处理居民的话时遇到了用户消息里说的情况，请据此给居民写一两句简短的回复："
            + "说明原因，并告诉他下一步怎么做（他可以直接在对话里告诉你）。只依据给出的内容，不要编造任何数据，不要提到“系统”“工具”或“intent”。"
            + "物资和服务点的名称照原样写，不要翻译（界面会换成它们的正式名称）。只用纯文本。"
            + ("en".equals(language) ? "Write the reply in English only, keeping item and service point names exactly as given."
                : "只用中文写这句回复。"));
        messages.add(json.createObjectNode().put("role", "user").put("content",
            "居民说：" + message + "\n遇到的情况：" + String.join("；", problems)));
        try {
            String text = plainText(model.completeText(messages));
            if (!text.isBlank()) return text;
        } catch (RuntimeException unavailable) {
            log.warn("LLM unavailable to phrase a refusal: {}", unavailable.getMessage());
        }
        return String.join("\n", problems);
    }

    /**
     * The conversation before the resident's latest message, oldest first, as a transcript the model
     * reads but is not part of. Given as its own chat turns, the replies the service wrote ("已提交申请 #1。")
     * were copied instead of read: 3 of 10 long conversations read right that way, against 10 of 10.
     */
    private String transcript(String conversationId, String latest) {
        List<StoredMessage> rows = new ArrayList<>(jdbc.sql("""
                SELECT role, content, tool_name FROM assistant_messages
                WHERE conversation_id=:id AND role IN ('USER','ASSISTANT') ORDER BY id DESC LIMIT 11
                """).param("id", conversationId)
            .query((rs, n) -> new StoredMessage(rs.getString("role"), rs.getString("content"))).list());
        // The latest message was saved already; it is given on its own.
        if (!rows.isEmpty() && "USER".equals(rows.get(0).role()) && latest.equals(rows.get(0).content())) rows.remove(0);
        StringBuilder text = new StringBuilder();
        rows.reversed().forEach(message -> text.append("USER".equals(message.role()) ? "居民：" : "助手：")
            .append(message.content().length() > 600 ? message.content().substring(0, 600) + "…" : message.content()).append("\n"));
        return text.toString().trim();
    }

    /** What one reply gathers: the parts of its text, what was looked up, an action to agree to, the phone's actions. */
    static final class Turn {
        final List<String> parts = new ArrayList<>();
        final List<AssistantModels.ToolExecution> executions = new ArrayList<>();
        final List<AssistantModels.ClientAction> actions = new ArrayList<>();
        AssistantModels.Confirmation confirmation;

        static Turn empty() {
            return new Turn();
        }
    }

    /** A reply composed in Chinese from live data or fixed text; the client shows it in the reply language. */
    private AssistantModels.ChatResponse reply(String conversationId, String content, Turn turn, String language,
                                               boolean modelUsed) {
        saveMessage(conversationId, "ASSISTANT", content, null);
        return new AssistantModels.ChatResponse(conversationId, content, turn.executions, turn.confirmation, false,
            language, modelUsed, turn.actions);
    }

    private void dropWaiting(long userId, String conversationId, String waiting) {
        if (waiting == null) return;
        confirmations.stopWaiting(userId, conversationId);
        confirmations.discard(userId, waiting);
    }

    /** Runs the action the resident agreed to and says what was done, or why it could not be. */
    private AssistantModels.ChatResponse confirmed(long userId, String conversationId, String token, String language) {
        Turn turn = new Turn();
        Object result;
        try {
            result = tools.confirm(userId, token);
        } catch (RuntimeException failure) {
            log.info("Confirmed action failed: {}", failure.getMessage());
            return reply(conversationId, "没能完成这项操作：" + reason(failure), turn, language, false);
        }
        turn.executions.add(new AssistantModels.ToolExecution("confirmed_action", result));
        String content;
        if (result instanceof SupplyRequestView request && request.status() == RequestStatus.CANCELLED) {
            content = "已取消申请 #" + request.residentNumber() + "。";
        } else if (result instanceof SupplyRequestView request) {
            content = "已提交申请 #" + request.residentNumber() + "。" + (request.decisionNote() == null ? "" : request.decisionNote());
        } else if (result instanceof ReservationService.ReservationReceipt receipt) {
            content = "已预约 " + receipt.itemName() + " × " + receipt.quantity() + "，领取码 " + receipt.pickupCode() + "，有效期至 "
                + DateTimeFormatter.ofPattern("M月d日 HH:mm").withZone(ZoneId.of("Asia/Shanghai")).format(receipt.expiresAt())
                + "。领取或配送送达时，请出示领取码。";
        } else if (result instanceof AssistantToolExecutor.FeedbackGiven given) {
            content = "已提交申请 #" + given.requestNumber() + " 的服务反馈（" + given.rating() + " 星），谢谢！";
            // The requests page marks a rated request from what the phone remembers.
            turn.actions.add(new AssistantModels.ClientAction("feedbackGiven", Map.of("requestId", given.requestId())));
        } else if (result instanceof AssistantToolExecutor.Profile profile) {
            content = "已更新资料：称呼“" + profile.displayName() + "”，家庭人数 " + profile.householdSize() + " 人。";
        } else if (result instanceof AssistantToolExecutor.ChosenPlace place) {
            content = "已把您的位置设为“" + place.address() + "”。";
            turn.actions.add(setPlace(place.latitude(), place.longitude(), place.address()));
        } else {
            content = "操作已确认并完成。";
        }
        return reply(conversationId, content, turn, language, false);
    }

    /** Why a confirmed action failed, in words for the resident. */
    static String reason(RuntimeException failure) {
        String message = failure instanceof ResponseStatusException status ? status.getReason() : failure.getMessage();
        if (failure instanceof jakarta.validation.ConstraintViolationException) return "填写的信息不完整或不正确，请重新告诉我。";
        if (failure instanceof org.springframework.dao.OptimisticLockingFailureException) return "申请状态刚刚发生了变化，请稍后再试。";
        if (message == null) return "请稍后再试。";
        if (message.contains("Confirmation expired")) return "确认已过期（需要在 5 分钟内确认），请重新告诉我要做什么。";
        if (message.contains("Feedback already given")) return "这个申请已经评价过了，每个申请只能评价一次。";
        if (message.contains("Feedback requires a fulfilled request")) return "申请还没有完成，完成后才能评价。";
        if (message.startsWith("Cannot transition")) return "申请状态已经变化，无法完成。";
        if (message.contains("not found")) return "没有找到这个申请。";
        return message.codePoints().anyMatch(c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN) ? message : "请稍后再试。";
    }

    enum Answer { YES, NO, UNSURE }

    private static final List<String> YES_WORDS = List.of("确认执行", "确认提交", "确认", "确定", "是的", "是", "对的", "对",
        "好的", "好啊", "好吧", "好", "可以的", "可以", "行的", "行", "没问题", "同意", "嗯嗯", "嗯", "恩", "提交", "执行",
        "就这样", "没错", "是这个", "去吧", "办吧", "保存", "yes", "yeah", "yep", "yup", "ok", "okay", "sure", "confirmed",
        "confirm", "goahead", "doit", "correct", "right", "submit", "fine", "alright", "y");
    private static final List<String> NO_WORDS = List.of("先不要", "暂时不要", "不要了", "不用了", "不要", "不用", "不了",
        "算了", "放弃", "不对", "不是", "不行", "别", "否", "不", "no", "nope", "nah", "dont", "stop", "nevermind", "notnow", "n");
    private static final List<String> FILLER_WORDS = List.of("谢谢", "麻烦", "了", "请", "吧", "啊", "呀", "的", "哈", "呢",
        "please", "thanks", "thankyou", "thank", "you", "it", "that", "is", "just");

    /**
     * A plain yes or no to the action waiting for the resident, read without a model: the message must
     * consist only of yes (or no) words and fillers. Anything else is left to the model. A bare "取消"
     * declines, except when the action itself is a cancellation, where it could mean either.
     */
    static Answer answerTo(String message, String waitingTool) {
        String text = message == null ? "" : message.toLowerCase().replaceAll("[\\p{P}\\p{S}\\s]+", "");
        if (text.isEmpty()) return Answer.UNSURE;
        boolean cancelling = "cancel_my_request".equals(waitingTool);
        if (text.matches("取消(吧|了)?|cancel")) return cancelling ? Answer.UNSURE : Answer.NO;
        if (consistsOf(text, YES_WORDS)) return Answer.YES;
        if (consistsOf(text, NO_WORDS)) return Answer.NO;
        return Answer.UNSURE;
    }

    private static boolean consistsOf(String text, List<String> unordered) {
        // Longest words first, so "okay" is not read as "ok" + "ay" nor "nope" as "no" + "pe".
        List<String> words = unordered.stream().sorted((a, b) -> b.length() - a.length()).toList();
        int at = 0;
        boolean any = false;
        outer:
        while (at < text.length()) {
            for (String word : words) {
                if (text.startsWith(word, at)) {
                    at += word.length();
                    any = true;
                    continue outer;
                }
            }
            for (String filler : FILLER_WORDS) {
                if (text.startsWith(filler, at)) {
                    at += filler.length();
                    continue outer;
                }
            }
            return false;
        }
        return any;
    }

    /** What the model needs besides the conversation: the time, the resident's place, the stock and any action waiting. */
    private String context(long userId, AssistantModels.ChatRequest input, ConfirmationService.PendingAction pending) {
        java.time.ZonedDateTime now = java.time.ZonedDateTime.now(org.isolatedareas.helphub.requests.ServiceWindow.ZONE);
        StringBuilder text = new StringBuilder("当前北京时间：")
            .append(now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd EEEE HH:mm", java.util.Locale.CHINA))).append("。\n");
        if (input.latitude() == null || input.longitude() == null) text.append("居民还没有设置位置。\n");
        else if (Boolean.TRUE.equals(input.placeIsDefault())) text.append("居民现在用的是系统随机分配的默认位置，还没有设置自己家的地址。\n");
        else text.append("居民保存的位置：").append(input.placeLabel() == null || input.placeLabel().isBlank() ? "已设置" : input.placeLabel()).append("。\n");
        if (tools.execute(userId, "check_inventory", json.createObjectNode()).result() instanceof List<?> stock) {
            List<String> names = stock.stream().filter(InventoryItemView.class::isInstance).map(InventoryItemView.class::cast)
                .map(InventoryItemView::name).distinct().toList();
            if (!names.isEmpty()) text.append("库存里现有的物资：").append(String.join("、", names)).append("。\n");
        }
        if (pending != null) text.append("居民有一项操作等待确认：").append(pending.summary()).append("。\n");
        return text.toString();
    }

    /** The last word the model gets: the language of any reply, written in that language. */
    static String replyRule(String language) {
        return "en".equals(language) ? "The resident wrote in English: if you write \"reply\", write it in English only."
            : "居民用中文提问：如果写 reply，只用中文写。";
    }

    /** Whether text is in the wrong language: mostly Chinese when English was asked for, or the reverse. */
    static boolean wrongLanguage(String content, String language) {
        int han = 0;
        int latin = 0;
        for (int i = 0; i < content.length(); i++) {
            char c = content.charAt(i);
            if (Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN) han++;
            else if (c < 128 && Character.isLetter(c)) latin++;
        }
        return "en".equals(language) ? han * 2 > latin : han == 0 && latin > 10;
    }

    // A reply saying something was done: the model cannot do anything itself, so it must name the intent.
    private static final Pattern CLAIM = Pattern.compile(
        "已(经)?(为您|帮您|给您)?(切换|设置|更新|修改|提交|取消|拨打|打开|导航|预约|评价|登记|记录)"
            + "|(has|have) been (set|updated|switched|submitted|cancelled|canceled|changed|recorded)"
            + "|I('ve| have) (set|updated|switched|submitted|cancelled|canceled|called|opened|changed|recorded)|is (now )?set to"
            + "|^\\W*(switched|changed|updated|cancelled|canceled|submitted|calling|opening) (to|your|the|it)\\b",
        Pattern.CASE_INSENSITIVE);

    /** The model's JSON, also when wrapped in a code fence or a sentence; null when there is none. */
    JsonNode parseUnderstanding(String raw) {
        if (raw == null) return null;
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start < 0 || end <= start) return null;
        try {
            JsonNode node = json.readTree(raw.substring(start, end + 1));
            return node.isObject() ? node : null;
        } catch (JsonProcessingException unreadable) {
            return null;
        }
    }

    /** What is wrong with an understanding, as a correction for the model, or null when nothing is. */
    static String problemWith(JsonNode understood, String message) {
        if (understood == null) return "你的输出不是 JSON。只输出一个 JSON 对象，格式：{\"actions\":[...],\"reply\":\"...\"}。";
        JsonNode actions = understood.path("actions");
        boolean chatOnly = !actions.isArray() || actions.isEmpty() || java.util.stream.StreamSupport.stream(actions.spliterator(), false)
            .allMatch(action -> action.path("intent").asText("chat").equalsIgnoreCase("chat"));
        if (!chatOnly) return null;
        String reply = understood.path("reply").asText("");
        if (CLAIM.matcher(reply).find()) {
            return "你的 reply 说已经办了某件事，但你自己什么都办不了，必须在 actions 里输出对应的 intent"
                + "（例如 update_profile、set_language、call_hotline、apply）。请重新输出 JSON。";
        }
        if (echoes(reply, message)) {
            return "你的 reply 只是重复了居民的话。想清楚居民要查什么或办什么，输出对应的 intent（例如 my_notifications、my_requests、stock）；"
                + "确实只是闲聊时，reply 要真正回答他。请重新输出 JSON。";
        }
        return null;
    }

    // "您有新消息吗？" for "有没有新消息": the reply says back what the resident asked.
    static boolean echoes(String reply, String message) {
        String said = reply.replaceAll("[\\p{P}\\p{S}\\s]|您|你|我|吗|呢|有没有|有", "");
        String asked = message == null ? "" : message.replaceAll("[\\p{P}\\p{S}\\s]|您|你|我|吗|呢|有没有|有", "");
        return !said.isEmpty() && (said.equals(asked) || (asked.length() >= 3 && said.length() <= asked.length() + 2 && said.contains(asked)));
    }

    /** A chat answer the model wrote in the wrong language, rewritten in the right one. */
    private String inLanguage(String text, String language) {
        if (!wrongLanguage(text, language)) return text;
        ArrayNode messages = json.createArrayNode();
        system(messages, "en".equals(language) ? "Rewrite the following in English, keeping its meaning. Output only the rewritten text."
            : "把下面这段话改写成中文，意思不变，只输出改写后的话。");
        messages.add(json.createObjectNode().put("role", "user").put("content", text));
        try {
            String rewritten = plainText(model.completeText(messages));
            return rewritten.isBlank() ? text : rewritten;
        } catch (RuntimeException unavailable) {
            return text;
        }
    }

    private void system(ArrayNode messages, String content) {
        messages.add(json.createObjectNode().put("role", "system").put("content", content));
    }

    private String liveAnswer(long userId, String tool, JsonNode args, AssistantModels.ChatRequest input,
                              List<AssistantModels.ToolExecution> executions) {
        return switch (tool) {
            case "check_inventory" -> {
                List<String> items = texts(args.path("items"));
                if (items.isEmpty()) items = texts(args.path("item"));
                if (items.isEmpty()) yield describeInventory(run(userId, tool, args, executions));
                yield describeAskedItems(run(userId, tool, json.createObjectNode(), executions), items);
            }
            case "list_service_points" -> describeServicePoints(run(userId, tool, args, executions));
            case "find_nearby_service_points" -> {
                if (input.latitude() == null || input.longitude() == null) yield NO_PLACE;
                ObjectNode near = json.createObjectNode().put("latitude", input.latitude()).put("longitude", input.longitude())
                    .put("radiusMeters", NEARBY_RADIUS_METERS);
                yield describeNearby(run(userId, tool, near, executions));
            }
            case "list_my_requests" -> describeRequests(run(userId, tool, json.createObjectNode(), executions));
            case "get_request_status" -> {
                String number = args.path("requestId").asText("").replaceAll("\\D", "");
                if (number.isEmpty()) number = extractRequestId(input.message());
                if (number == null || number.isEmpty() || number.length() > 9) {
                    yield describeRequests(run(userId, "list_my_requests", json.createObjectNode(), executions));
                }
                yield describeRequest(userId, Integer.parseInt(number), executions);
            }
            case "list_my_reservations" -> describeReservations(run(userId, tool, json.createObjectNode(), executions));
            case "list_my_notifications" -> describeNotices(run(userId, tool, json.createObjectNode(), executions));
            case "explain_how" -> {
                String guide = GUIDES.get(args.path("topic").asText("").trim().toLowerCase());
                if (guide == null) throw new IllegalArgumentException("没有这个主题，可选：" + String.join("、", GUIDES.keySet()));
                executions.add(new AssistantModels.ToolExecution(tool, Map.of("topic", args.path("topic").asText())));
                yield guide;
            }
            case "get_my_profile" -> describeProfile(run(userId, tool, json.createObjectNode(), executions), input);
            case "track_delivery" -> {
                ObjectNode which = json.createObjectNode();
                String number = args.path("requestId").asText("").replaceAll("\\D", "");
                if (number.isEmpty() && extractRequestId(input.message()) != null) number = extractRequestId(input.message());
                if (!number.isEmpty() && number.length() <= 9) which.put("requestId", Integer.parseInt(number));
                try {
                    yield describeDelivery(run(userId, tool, which, executions));
                } catch (IllegalArgumentException notFound) {
                    yield "未找到属于您的申请 #" + number + "，请检查编号后重试。";
                }
            }
            default -> throw new IllegalArgumentException("Not a lookup: " + tool);
        };
    }

    /** Prepares an action for the resident to agree to; the reply then asks them to. */
    private String prepare(long userId, String tool, JsonNode args, AssistantModels.ChatRequest input, Turn turn) {
        if (turn.confirmation != null) return null;  // One action at a time.
        JsonNode arguments = withSavedPlace(tool, withWindow(tool, args), input);
        // The resident's own words, to pick one of several products when the model passes on only the general name.
        if (arguments.isObject()) ((ObjectNode) arguments).put("residentWords", input.message());
        AssistantToolExecutor.ToolResult result = tools.execute(userId, tool, arguments);
        turn.confirmation = result.confirmation();
        return null;
    }

    /**
     * A request's time as the form offers it: "date" and "slot" (morning 09:00–12:00 or afternoon
     * 13:00–17:00) become the window; neither means as soon as possible.
     */
    static JsonNode withWindow(String tool, JsonNode args) {
        if (!"prepare_supply_request".equals(tool) || !args.isObject()) return args;
        ObjectNode copy = ((ObjectNode) args).deepCopy();
        String date = copy.path("date").asText("").trim();
        String slot = copy.path("slot").asText("").trim().toUpperCase();
        copy.remove("date");
        copy.remove("slot");
        if (date.isEmpty() && slot.isEmpty()) return copy;
        if (date.isEmpty()) date = java.time.LocalDate.now(org.isolatedareas.helphub.requests.ServiceWindow.ZONE).toString();
        if (!slot.equals("MORNING") && !slot.equals("AFTERNOON")) {
            throw new IllegalArgumentException("请问居民要上午（09:00–12:00）还是下午（13:00–17:00）");
        }
        java.time.LocalDate day;
        try {
            day = java.time.LocalDate.parse(date);
        } catch (java.time.format.DateTimeParseException unreadable) {
            throw new IllegalArgumentException("请问居民要哪一天（最多提前 7 天）");
        }
        boolean morning = slot.equals("MORNING");
        copy.put("preferredStart", day.atTime(morning ? 9 : 13, 0).atZone(org.isolatedareas.helphub.requests.ServiceWindow.ZONE)
            .toOffsetDateTime().toString());
        copy.put("preferredEnd", day.atTime(morning ? 12 : 17, 0).atZone(org.isolatedareas.helphub.requests.ServiceWindow.ZONE)
            .toOffsetDateTime().toString());
        return copy;
    }

    /** Address search and choice, and what only the phone can do. */
    private String direct(long userId, String conversationId, String tool, JsonNode args, AssistantModels.ChatRequest input,
                          Turn turn) {
        return switch (tool) {
            case "search_address" -> {
                String address = args.path("address").asText("").trim();
                if (address.isEmpty()) throw new IllegalArgumentException("请问居民家的地址（小区或道路、门牌号）");
                // The map search reads Chinese addresses only.
                if (address.codePoints().noneMatch(c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN)) {
                    address = chineseAddress(address);
                }
                var found = (AssistantToolExecutor.AddressMatches) tools.execute(userId, tool,
                    json.createObjectNode().put("address", address)).result();
                if (!found.available()) yield "地址查询暂时不可用。请在首页“当前位置”右侧点“设置”，在地图上拖动选出您家的位置。";
                if (found.places().isEmpty()) yield "没有找到“" + found.query() + "”这个地址。可以只说小区名，或者道路加门牌号再试。";
                List<AssistantToolExecutor.FoundPlace> served = found.places().stream()
                    .filter(place -> place.nearestPointMeters() <= NEARBY_RADIUS_METERS).toList();
                if (served.isEmpty()) {
                    yield "找到的地点离最近的服务点都超过 10 公里，不在服务范围内。请确认地址是否正确，例如加上“青浦区”再说一次。";
                }
                ObjectNode remembered = json.createObjectNode().put("query", found.query());
                remembered.set("places", json.valueToTree(served));
                confirmations.rememberPlaces(userId, conversationId, remembered);
                if (served.size() == 1) {
                    turn.confirmation = placeConfirmation(userId, found.query(), served.get(0));
                    yield null;
                }
                StringBuilder list = new StringBuilder("找到这几个地点，请告诉我是第几个：");
                for (int i = 0; i < served.size(); i++) {
                    list.append("\n").append(i + 1).append(". ").append(placeLine(served.get(i)));
                }
                yield list.toString();
            }
            case "choose_address" -> {
                JsonNode remembered = confirmations.places(userId, conversationId);
                if (remembered == null) throw new IllegalArgumentException("没有可选的地点，请先用 search_address 按居民说的地址搜索");
                int choice = args.path("choice").asInt(0);
                JsonNode places = remembered.path("places");
                if (choice < 1 || choice > places.size()) {
                    throw new IllegalArgumentException("只有 " + places.size() + " 个地点可选，请问居民是第几个");
                }
                JsonNode place = places.get(choice - 1);
                String address = remembered.path("query").asText();
                turn.actions.add(setPlace(place.path("latitude").asDouble(), place.path("longitude").asDouble(), address));
                yield "已把您的位置设为“" + address + "”（" + placeLine(json.convertValue(place, AssistantToolExecutor.FoundPlace.class)) + "）。";
            }
            case "set_language" -> {
                String wanted = args.path("language").asText("").trim().toLowerCase();
                String value = wanted.startsWith("en") || wanted.contains("英") ? "en"
                    : wanted.startsWith("zh") || wanted.startsWith("ch") || wanted.contains("中") ? "zh" : null;
                if (value == null) throw new IllegalArgumentException("只能切换为中文（zh）或英文（en）");
                turn.actions.add(new AssistantModels.ClientAction("setLanguage", Map.of("language", value)));
                yield "en".equals(value) ? "已切换为英文界面。" : "已切换为中文界面。";
            }
            case "call_hotline" -> {
                turn.actions.add(new AssistantModels.ClientAction("call", Map.of("phone", hotline)));
                yield "正在拨打社区服务热线 " + hotline + "。";
            }
            case "open_navigation" -> {
                String keyword = args.path("keyword").asText("").trim();
                Map<?, ?> point = destination(userId, keyword, input);
                if (point == null) {
                    yield keyword.isEmpty() ? NO_PLACE : "没有找到名称或地址包含“" + keyword + "”的服务点。可以问“青浦有哪些服务点”查看全部。";
                }
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("latitude", ((Number) point.get("latitude")).doubleValue());
                data.put("longitude", ((Number) point.get("longitude")).doubleValue());
                data.put("name", String.valueOf(point.get("name")));
                data.put("address", String.valueOf(point.get("address")));
                turn.actions.add(new AssistantModels.ClientAction("openLocation", data));
                yield "正在打开微信地图，导航到" + point.get("name") + "（" + point.get("address") + "）。";
            }
            case "open_page" -> {
                String page = args.path("page").asText("").trim().toLowerCase();
                String[] target = PAGES.get(page);
                if (target == null) throw new IllegalArgumentException("没有这个页面：" + page + "。可选：" + String.join("、", PAGES.keySet()));
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("page", page);
                data.put("path", target[0]);
                if (page.equals("feedback") || page.equals("track")) {
                    String number = args.path("requestId").asText("").replaceAll("\\D", "");
                    if (number.isEmpty() || number.length() > 9) throw new IllegalArgumentException("打开这个页面需要申请编号，请问居民是哪个申请");
                    var request = (SupplyRequestView) tools.execute(userId, "get_request_status",
                        json.createObjectNode().put("requestId", Integer.parseInt(number))).result();
                    data.put("requestId", request.id());
                    data.put("requestNumber", request.residentNumber());
                }
                turn.actions.add(new AssistantModels.ClientAction("openPage", data));
                yield "正在打开“" + target[1] + "”页。";
            }
            default -> throw new IllegalArgumentException("Not a direct tool: " + tool);
        };
    }

    /** An address given in English ("88 Qinghu Road, Qingpu"), in Chinese for the map search. */
    private String chineseAddress(String address) {
        ArrayNode messages = json.createArrayNode();
        system(messages, "把居民用英文或拼音写的地址翻译成中文地址（例如 88 Qinghu Road, Qingpu → 青浦区青湖路88号）。只输出中文地址，不要输出别的。");
        messages.add(json.createObjectNode().put("role", "user").put("content", address));
        try {
            String chinese = model.completeText(messages).trim().replaceAll("^[\"“”']+|[\"“”'。]+$", "");
            return chinese.isEmpty() || chinese.length() > 80 ? address : chinese;
        } catch (RuntimeException unavailable) {
            return address;
        }
    }

    /** The service point to navigate to: the one named, else the nearest to the saved place. */
    private Map<?, ?> destination(long userId, String keyword, AssistantModels.ChatRequest input) {
        Object points;
        if (!keyword.isEmpty()) {
            points = tools.execute(userId, "list_service_points", json.createObjectNode().put("keyword", keyword)).result();
        } else if (input.latitude() != null && input.longitude() != null) {
            points = tools.execute(userId, "find_nearby_service_points", json.createObjectNode().put("latitude", input.latitude())
                .put("longitude", input.longitude()).put("radiusMeters", 50_000)).result();
        } else {
            return null;
        }
        return points instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof Map<?, ?> first ? first : null;
    }

    private AssistantModels.Confirmation placeConfirmation(long userId, String address, AssistantToolExecutor.FoundPlace place) {
        ObjectNode args = json.createObjectNode().put("latitude", place.latitude()).put("longitude", place.longitude())
            .put("address", address).put("title", place.title() + "，" + place.address());
        return tools.execute(userId, "set_my_location", args).confirmation();
    }

    // "青湖苑：上海市青浦区青湖路88弄，离最近的服务点约 1.2 公里"
    private static String placeLine(AssistantToolExecutor.FoundPlace place) {
        return place.title() + "：" + place.address() + "，离最近的服务点约 "
            + String.format(java.util.Locale.ROOT, "%.1f", place.nearestPointMeters() / 1000.0) + " 公里";
    }

    private static AssistantModels.ClientAction setPlace(double latitude, double longitude, String address) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("latitude", latitude);
        data.put("longitude", longitude);
        data.put("address", address);
        return new AssistantModels.ClientAction("setPlace", data);
    }

    private String describeRequest(long userId, int number, List<AssistantModels.ToolExecution> executions) {
        try {
            AssistantToolExecutor.ToolResult result = tools.execute(userId, "get_request_status",
                json.createObjectNode().put("requestId", number));
            executions.add(new AssistantModels.ToolExecution("get_request_status", result.result()));
            return describeRequest(result.result());
        } catch (IllegalArgumentException notFound) {
            return "未找到属于您的申请 #" + number + "，请检查编号后重试。";
        }
    }

    /** "items": ["大米", "口罩"], or one name as a plain string. */
    private static List<String> texts(JsonNode node) {
        List<String> values = new ArrayList<>();
        if (node.isArray()) node.forEach(value -> values.add(value.asText("").trim()));
        else if (node.isTextual()) values.addAll(List.of(node.asText().split("[,，、;；]")));
        return values.stream().map(String::trim).filter(value -> !value.isEmpty()).distinct().toList();
    }

    /**
     * The resident's saved place for the tools that need one. A model never supplies coordinates: it
     * cannot know them, and one was seen to make them up.
     */
    private JsonNode withSavedPlace(String tool, JsonNode args, AssistantModels.ChatRequest input) {
        if (!PLACE_TOOLS.contains(tool)) return args;
        if (input.latitude() == null || input.longitude() == null) {
            throw new IllegalArgumentException("居民还没有设置位置，无法申请。请问居民家的详细地址，用 search_address 帮他设置，设置好后再申请。");
        }
        if (Boolean.TRUE.equals(input.placeIsDefault()) && "DELIVERY".equalsIgnoreCase(args.path("fulfillmentMethod").asText(""))) {
            throw new IllegalArgumentException("居民还在用系统随机分配的默认位置，补给车不知道送到哪里。请先问居民家的详细地址，"
                + "用 search_address 设置好位置，再申请配送。");
        }
        ObjectNode copy = args.isObject() ? ((ObjectNode) args).deepCopy() : json.createObjectNode();
        copy.put("latitude", input.latitude()).put("longitude", input.longitude());
        if (input.placeLabel() != null && !input.placeLabel().isBlank() && !Boolean.TRUE.equals(input.placeIsDefault())) {
            copy.put("approximateAddress", input.placeLabel());
        } else {
            copy.remove("approximateAddress");
        }
        return copy;
    }

    private static String toolName(JsonNode call) {
        return call.path("function").path("name").asText();
    }

    static final String EMERGENCY = "如有人身危险或紧急医疗情况，请立即拨打 120；涉及治安或人身安全请拨打 110。这里可以继续帮您查询青浦公益物资和服务点，但不能代替紧急救援。";
    static final String NO_PLACE = "您还没有设置位置。告诉我您家的详细地址（例如：青浦区青湖路 88 弄 5 号 302 室），我来帮您设置。";
    private static final String FLOW = """
        使用流程：
        ① 在“求助”页从现有物资中选择所需物资和数量，设置位置，并选择“到点自取”或“补给车配送”；
        ② 工作人员人工审核：批准后系统锁定离你最近且有货的服务点的物资，并发放 6 位领取码（保留 48 小时）；未通过会在“进度”页说明；
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

    // Clear signs of danger only: "急救包" is an item and "help me" asks for help with the app.
    private static final List<String> DANGER_ZH = List.of("救命", "昏迷", "晕倒", "晕过去", "不省人事", "休克", "大出血",
        "流血不止", "血流不止", "着火", "失火", "火灾", "报警", "呼吸困难", "喘不上气", "喘不过气", "心脏病发", "心梗",
        "中风", "抽搐", "自杀", "想死", "不想活", "轻生", "家暴", "被打", "打120", "打110", "拨打120", "拨打110",
        "叫救护车", "救护车", "有危险", "很危险", "生命危险");
    private static final Pattern DANGER_EN = Pattern.compile(
        "\\b(danger|dying|unconscious|passed out|faint(ed|ing)?|collapsed|bleed(ing)?|fire|police|ambulance|"
            + "heart attack|stroke|seizure|can'?t breathe|cannot breathe|not breathing|overdose|suicid(e|al)|"
            + "kill myself|emergency services|call 1[12]0)\\b",
        Pattern.CASE_INSENSITIVE);

    /** Whether the message says someone is in danger and needs 120 or 110 now. */
    static boolean inDanger(String message) {
        if (message == null) return false;
        String compact = message.replaceAll("\\s+", "");
        return DANGER_ZH.stream().anyMatch(compact::contains) || DANGER_EN.matcher(message).find();
    }

    /**
     * The language to answer in: the one the resident wrote in, whatever the interface shows. Each
     * Chinese character counts for two Latin letters; a tie (or a message of digits) follows the interface.
     */
    static String replyLanguage(String message, String interfaceLanguage) {
        int han = 0;
        int latin = 0;
        String text = message == null ? "" : message;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN) han++;
            else if (c < 128 && Character.isLetter(c)) latin++;
        }
        if (han * 2 > latin) return "zh";
        if (latin > han * 2) return "en";
        return "en".equalsIgnoreCase(interfaceLanguage) ? "en" : "zh";
    }

    private AssistantModels.ChatResponse fallback(long userId, String conversationId, String message, String language) {
        List<AssistantModels.ToolExecution> executions = new ArrayList<>();
        String found = lookup(userId, message, executions);
        Turn turn = new Turn();
        turn.executions.addAll(executions);
        return reply(conversationId, found == null ? HELP : found, turn, language, false);
    }

    /**
     * What the keyword lookup should read when no model answers: a question asked in English arrives
     * with the same question in Chinese keywords. A mapping to the emergency answer is only kept when
     * the English itself is about danger, since "help me set my location" asks for help with the app.
     */
    static String lookupText(AssistantModels.ChatRequest input) {
        String mapped = input.lookupText();
        if (mapped != null && !mapped.equals(input.message()) && mapped.contains("救命") && !inDanger(input.message())) {
            return input.message();
        }
        return mapped;
    }

    /** The keyword lookup's answer, for tests. */
    String lookupForTest(long userId, String message) {
        return lookup(userId, message, new ArrayList<>());
    }

    /** The answer from live data for a question it recognises, or null when it does not. */
    private String lookup(long userId, String message, List<AssistantModels.ToolExecution> executions) {
        String content;
        if (inDanger(message)) {
            content = EMERGENCY;
        } else if (containsAny(message, "通知", "消息", "提醒", "notification", "notice")) {
            content = describeNotices(run(userId, "list_my_notifications", json.createObjectNode(), executions));
        } else if (containsAny(message, "领取码", "预约记录", "我的预约", "预约了什么", "预约状态")) {
            content = describeReservations(run(userId, "list_my_reservations", json.createObjectNode(), executions));
        } else if (asksHowTo(message)) {
            content = FLOW;
        } else if (containsAny(message, "申请", "进度", "状态", "单子", "订单", "工单", "request")) {
            String requestId = extractRequestId(message);
            if (requestId != null) {
                content = describeRequest(userId, Integer.parseInt(requestId), executions);
            } else {
                content = describeRequests(run(userId, "list_my_requests", json.createObjectNode(), executions));
            }
        } else {
            // Residents ask for things by the names they see in the app ("口罩", "保暖毯", "纸尿裤"),
            // most of which no fixed keyword list covers, so look the question up in the stock itself.
            Object stock = tools.execute(userId, "check_inventory", json.createObjectNode()).result();
            List<InventoryItemView> named = matchedItems(stock, message);
            if (!named.isEmpty()) {
                executions.add(new AssistantModels.ToolExecution("check_inventory", stock));
                content = describeNamedInventory(named);
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
                content = null;
            }
        }
        return content;
    }

    /** The mini program shows text as it is, so Markdown a model adds anyway (bold, headings, code) is removed. */
    static String plainText(String content) {
        return content.replace("**", "").replace("__", "").replace("`", "")
            .replaceAll("(?m)^\\s{0,3}#{1,6}\\s*", "").trim();
    }

    /**
     * "怎么申请" asks how the service works; "我的申请怎么样了" / "申请到哪一步了" asks about the resident's
     * own requests, even though both mention 申请 and 怎么.
     */
    static boolean asksHowTo(String message) {
        String text = message == null ? "" : message;
        boolean how = java.util.stream.Stream.of("怎么", "如何", "流程", "步骤", "怎样").anyMatch(text::contains);
        boolean topic = java.util.stream.Stream.of("申请", "预约", "领", "提交", "配送", "送", "求助", "用").anyMatch(text::contains);
        boolean status = java.util.stream.Stream.of("进度", "状态", "到哪", "怎么样", "批了", "批准了吗").anyMatch(text::contains);
        return how && topic && !status;
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

    // Parts of item names that say nothing about what the item is.
    private static final List<String> GENERIC_NAME_PARTS = List.of("基础", "套装", "组合", "综合", "用品");

    /** The item itself in a stock name: "公益纯牛奶 250 毫升×12 盒" -> "纯牛奶", "医用外科口罩 50 只" -> "医用外科口罩". */
    static String coreName(String name) {
        return name == null ? "" : name.replace("公益", "").replaceAll("[\\s\\d].*$", "").trim();
    }

    /**
     * Stock rows the question asks about: every item it names in full, otherwise those whose name shares
     * the longest run of at least two characters with it, e.g. "有口罩吗" -> 医用外科口罩, "纸尿裤" -> both
     * diaper sizes. Empty when no name matches.
     */
    // Other words residents use for stocked items, so "卫生纸" finds 抽纸 and "尿不湿" the diapers.
    private static final Map<String, String> ITEM_SYNONYMS = Map.ofEntries(
        Map.entry("卫生纸", "抽纸"), Map.entry("纸巾", "抽纸"), Map.entry("面巾纸", "抽纸"), Map.entry("餐巾纸", "抽纸"),
        Map.entry("手纸", "抽纸"), Map.entry("尿不湿", "纸尿裤"), Map.entry("尿布", "纸尿裤"), Map.entry("面条", "挂面"),
        Map.entry("毯子", "保暖毯"), Map.entry("被子", "保暖毯"), Map.entry("电筒", "手电筒"), Map.entry("雨披", "雨衣"),
        Map.entry("消毒水", "消毒液"), Map.entry("酒精", "消毒液"), Map.entry("洗衣粉", "洗衣液"), Map.entry("肥皂", "香皂"),
        Map.entry("体温表", "体温计"), Map.entry("温度计", "体温计"), Map.entry("饼干", "苏打饼干"), Map.entry("罐头", "午餐肉罐头"),
        Map.entry("矿泉水", "饮用水"), Map.entry("纯净水", "饮用水"), Map.entry("牛奶", "纯牛奶"), Map.entry("奶", "纯牛奶"),
        Map.entry("燕麦", "燕麦片"), Map.entry("麦片", "燕麦片"), Map.entry("急救箱", "急救用品包"), Map.entry("药箱", "医疗用品包"),
        Map.entry("牙刷", "牙膏牙刷"), Map.entry("牙膏", "牙膏牙刷"), Map.entry("姨妈巾", "卫生巾"), Map.entry("卫生棉", "卫生巾"),
        Map.entry("油", "食用油"), Map.entry("盐", "食盐"), Map.entry("米", "大米"), Map.entry("水", "饮用水"));

    static List<InventoryItemView> matchedItems(Object value, String message) {
        if (!(value instanceof List<?> values) || message == null) return List.of();
        List<InventoryItemView> direct = matchedItemsAsWritten(values, message);
        if (!direct.isEmpty()) return direct;
        // Nothing by the resident's own word: try the stock's word for the same thing.
        String asStocked = message;
        for (Map.Entry<String, String> synonym : ITEM_SYNONYMS.entrySet()) {
            if (message.equals(synonym.getKey()) || (synonym.getKey().length() > 1 && message.contains(synonym.getKey()))) {
                asStocked = asStocked.replace(synonym.getKey(), synonym.getValue());
            }
        }
        return asStocked.equals(message) ? List.of() : matchedItemsAsWritten(values, asStocked);
    }

    private static List<InventoryItemView> matchedItemsAsWritten(List<?> values, String message) {
        // Every item named in full ("有挂面、方便面吗") is answered, not only the longest name.
        List<InventoryItemView> whole = values.stream().filter(InventoryItemView.class::isInstance)
            .map(InventoryItemView.class::cast)
            .filter(item -> coreName(item.name()).length() > 1 && message.contains(coreName(item.name()))).toList();
        if (!whole.isEmpty()) return whole;
        int best = 2;
        List<InventoryItemView> matches = new ArrayList<>();
        for (Object row : values) {
            if (!(row instanceof InventoryItemView item)) continue;
            int length = longestSharedRun(coreName(item.name()), message);
            if (length > best) {
                best = length;
                matches.clear();
            }
            if (length == best) matches.add(item);
        }
        return matches;
    }

    private static int longestSharedRun(String name, String message) {
        for (int length = name.length(); length > 1; length--) {
            for (int start = 0; start + length <= name.length(); start++) {
                String part = name.substring(start, start + length);
                if (!GENERIC_NAME_PARTS.contains(part) && message.contains(part)) return length;
            }
        }
        return 0;
    }

    /**
     * Answers a question about the items the resident named ("大米", "口罩"): those in stock with every
     * point that holds them, those all reserved, and those not stocked at all, each said plainly.
     */
    static String describeAskedItems(Object stock, List<String> terms) {
        List<InventoryItemView> found = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (String term : terms) {
            List<InventoryItemView> matches = matchedItems(stock, term);
            if (matches.isEmpty()) missing.add(term);
            matches.stream().filter(item -> !found.contains(item)).forEach(found::add);
        }
        List<String> parts = new ArrayList<>();
        if (!found.isEmpty()) parts.add(describeNamedInventory(found));
        missing.forEach(term -> parts.add(notStocked(term)));
        return String.join("\n\n", parts);
    }

    private static String notStocked(String term) {
        return "目前没有名称包含“" + term + "”的物资。您可以问“现在有哪些物资”查看全部，"
            + "或在“求助”页提交具体需求，我们会记录并跟进。";
    }

    /** Answers a question about particular items, saying so plainly when some have all been reserved. */
    static String describeNamedInventory(List<InventoryItemView> items) {
        List<String> available = items.stream().filter(item -> item.freeQuantity() > 0).map(InventoryItemView::name).distinct().toList();
        List<String> reserved = items.stream().map(InventoryItemView::name).distinct()
            .filter(name -> !available.contains(name)).toList();
        List<String> parts = new ArrayList<>();
        if (!available.isEmpty()) parts.add(describeInventory(items));
        if (!reserved.isEmpty()) {
            parts.add(String.join("、", reserved) + " 目前已全部被预约，暂时无法领取；您可以在“求助”页提交需求，我们会记录并跟进。");
        }
        return String.join("\n\n", parts);
    }

    /** Answers a question about one kind of item, e.g. "有大米吗", listing every point that stocks it. */
    static String describeInventory(Object value, String term) {
        List<InventoryItemView> matches = value instanceof List<?> values ? values.stream()
            .filter(InventoryItemView.class::isInstance).map(InventoryItemView.class::cast)
            .filter(item -> item.name().contains(term)).toList() : List.of();
        if (matches.isEmpty()) return notStocked(term);
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
        // One line per product, however many service points stock it, so the whole list fits.
        Map<String, List<InventoryItemView>> byName = new java.util.LinkedHashMap<>();
        available.forEach(item -> byName.computeIfAbsent(item.name(), name -> new ArrayList<>()).add(item));
        StringBuilder answer = new StringBuilder("当前可免费领取：\n");
        byName.forEach((name, rows) -> {
            answer.append("• ").append(name).append("：");
            for (int i = 0; i < rows.size(); i++) {
                InventoryItemView item = rows.get(i);
                if (i > 0) answer.append("、");
                answer.append(item.freeQuantity()).append(item.unit()).append("（").append(item.servicePointName()).append("）");
            }
            answer.append("\n");
        });
        answer.append("请在“求助”页提交需求；工作人员审核批准后，会锁定库存并发放领取码。");
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
        StringBuilder answer = new StringBuilder("申请 #" + request.residentNumber() + "：" + request.itemDescription() + "，数量 "
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
                answer.append("• 申请 #").append(reservation.requestNumber()).append("：").append(reservation.itemName())
                    .append(" × ").append(reservation.quantity()).append("，").append(reservationStatus(reservation.status()));
                if (reservation.pickupCode() != null && ("HELD".equals(reservation.status())
                    || "CONFIRMED".equals(reservation.status()))) {
                    answer.append("，领取码 ").append(reservation.pickupCode()).append("，有效期至 ")
                        .append(format.format(reservation.expiresAt())).append("，物资来自 ")
                        .append(reservation.servicePointName());
                }
                answer.append("\n");
            });
        answer.append("领取或配送送达时，请出示领取码。");
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
            .forEach(point -> answer.append("• ").append(pointLine(point)).append("\n"));
        answer.append("可在首页地图查看位置并导航；选择“到点自取”时请在营业时间内前往。");
        return answer.toString();
    }

    /** The service points nearest the resident's saved place, nearest first, with how far each is. */
    static String describeNearby(Object value) {
        if (!(value instanceof List<?> points) || points.isEmpty()) {
            return "您的位置 10 公里内没有服务点，不在服务范围内。可以问“青浦有哪些服务点”查看全部点位。";
        }
        StringBuilder answer = new StringBuilder("离您最近的服务点：\n");
        points.stream().filter(Map.class::isInstance).map(Map.class::cast).limit(5)
            .forEach(point -> answer.append("• ").append(pointLine(point)).append("\n"));
        answer.append("可在首页地图查看位置并导航；选择“到点自取”时请在营业时间内前往。");
        return answer.toString();
    }

    private static final Map<String, String> NOTICE_NAMES = Map.ofEntries(
        Map.entry("REQUEST_RECEIVED", "需求已收到"), Map.entry("REQUEST_CREATED", "需求已收到"),
        Map.entry("REQUEST_UNDER_REVIEW", "需求审核中"), Map.entry("REQUEST_APPROVED", "需求已批准，可预约物资"),
        Map.entry("REQUEST_SCHEDULED", "需求已安排"), Map.entry("REQUEST_FULFILLED", "需求已完成"),
        Map.entry("REQUEST_REJECTED", "需求未通过"), Map.entry("REQUEST_CANCELLED", "需求已取消"),
        Map.entry("REQUEST_STATUS_CHANGED", "申请进度更新"), Map.entry("RESERVATION_HELD", "预约成功"),
        Map.entry("PAYMENT_SUCCEEDED", "支付已完成"), Map.entry("PAYMENT_REFUNDED", "退款已完成"),
        Map.entry("ROUTE_READY", "服务路线已生成"), Map.entry("GENERAL_UPDATE", "服务更新"));

    /** The resident's latest notices, as the notices page names them. */
    static String describeNotices(Object value) {
        if (!(value instanceof List<?> rows) || rows.isEmpty()) return "您还没有收到任何通知。";
        DateTimeFormatter format = DateTimeFormatter.ofPattern("M月d日 HH:mm").withZone(ZoneId.of("Asia/Shanghai"));
        StringBuilder answer = new StringBuilder("您最近的通知：\n");
        rows.stream().filter(AssistantToolExecutor.Notice.class::isInstance).map(AssistantToolExecutor.Notice.class::cast)
            .forEach(notice -> {
                answer.append("• ").append(format.format(notice.createdAt())).append(" ")
                    .append(NOTICE_NAMES.getOrDefault(notice.template(), "服务更新"));
                JsonNode number = notice.payload() == null ? null : notice.payload().path("requestNumber");
                if (number != null && number.canConvertToInt()) answer.append("（申请 #").append(number.asInt()).append("）");
                answer.append("\n");
            });
        answer.append("也可以在“我的”→“进度通知”查看。");
        return answer.toString();
    }

    /** The resident's name, household and the place the assistant uses for them. */
    static String describeProfile(Object value, AssistantModels.ChatRequest input) {
        if (!(value instanceof AssistantToolExecutor.Profile profile)) return "资料暂时无法读取，请稍后再试。";
        String place;
        if (input.latitude() == null || input.longitude() == null) place = "还没有设置";
        else if (Boolean.TRUE.equals(input.placeIsDefault())) place = "系统分配的默认位置（还没有设置您家的地址）";
        else place = input.placeLabel() == null || input.placeLabel().isBlank() ? "已设置" : input.placeLabel();
        return "您的资料：称呼“" + profile.displayName() + "”，家庭人数 " + profile.householdSize() + " 人。\n当前位置：" + place + "。";
    }

    /** Where the cart bringing a request is, or why there is none. */
    static String describeDelivery(Object value) {
        if (!(value instanceof AssistantToolExecutor.Delivery delivery) || delivery.request() == null) {
            return "您还没有选择补给车配送的申请。";
        }
        SupplyRequestView request = delivery.request();
        String number = "申请 #" + request.residentNumber();
        if (request.fulfillmentMethod() == FulfillmentMethod.PICKUP) {
            return number + " 是到点自取，没有配送车辆" + (request.assignedServicePointName() == null ? "。"
                : "，请到" + request.assignedServicePointName() + "出示领取码领取。");
        }
        CartTripService.Tracking trip = delivery.trip();
        if (trip == null) {
            return switch (request.status()) {
                case SUBMITTED, UNDER_REVIEW -> number + " 还在等待审核，批准后系统才会安排补给车。";
                case APPROVED, SCHEDULED -> number + " 已批准，补给车还没有出发，系统会自动安排。";
                case FULFILLED -> number + " 已经送达，本次服务已完成。";
                case REJECTED -> number + " 未通过，不会配送。";
                case CANCELLED -> number + " 已取消，不会配送。";
            };
        }
        if (trip.delivered()) return number + " 的物资已由" + trip.cartName() + "送达。";
        String arrival = trip.estimatedArrival() == null ? ""
            : "，预计 " + DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.of("Asia/Shanghai")).format(trip.estimatedArrival()) + " 送达";
        return number + "：" + trip.cartName() + "，" + trip.statusText() + arrival + "。可在地图页查看车辆位置。";
    }

    // "邻需通·夏阳公益服务点：上海市青浦区青松路245号附近，约 1.2 公里，营业时间 08:30–16:30"
    private static String pointLine(Map<?, ?> point) {
        StringBuilder line = new StringBuilder().append(point.get("name")).append("：").append(point.get("address"));
        if (point.get("distanceMeters") instanceof Number meters) {
            line.append("，约 ").append(String.format(java.util.Locale.ROOT, "%.1f", meters.doubleValue() / 1000)).append(" 公里");
        }
        if (point.get("opensAt") != null && point.get("closesAt") != null) {
            line.append("，营业时间 ").append(clock(point.get("opensAt"))).append("–").append(clock(point.get("closesAt")));
        }
        return line.toString();
    }

    private static String clock(Object time) {
        return time instanceof java.time.LocalTime local ? local.format(DateTimeFormatter.ofPattern("HH:mm")) : String.valueOf(time);
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

    private void saveMessage(String conversationId, String role, String content, String toolName) {
        jdbc.sql("""
                INSERT INTO assistant_messages (conversation_id, role, content, tool_name)
                VALUES (:conversationId, :role, :content, :toolName)
                """).param("conversationId", conversationId).param("role", role)
            .param("content", content).param("toolName", toolName).update();
    }

    private JsonNode parseArguments(String value) {
        try {
            return json.readTree(value.isBlank() ? "{}" : value);
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
        system(messages, "用户本次消息中提到的申请编号是 " + requestId + "，查询申请状态时以此编号调用 get_request_status。");
    }

    private static String extractRequestId(String message) {
        Matcher matcher = REQUEST_ID.matcher(message == null ? "" : message);
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
