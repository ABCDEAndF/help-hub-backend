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

/**
 * Chat completions from OpenAI-compatible providers. The assistant asks for two things only: a JSON
 * reading of what the resident wants, and now and then a sentence to say to them. A small free model
 * reads a message into JSON reliably where it was seen to skip native tool calls and answer from
 * nothing, so no tools are offered.
 */
@Component
public class OpenAiCompatibleClient {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(OpenAiCompatibleClient.class);
    /** A model whose quota is used up, or whose key is refused, is not asked again for this long. */
    static final java.time.Duration QUOTA_PAUSE = java.time.Duration.ofMinutes(30);
    /** After a timeout or a server error the model is tried again soon. */
    static final java.time.Duration ERROR_PAUSE = java.time.Duration.ofMinutes(1);
    /** A busy model (429: rate limit, or a free model allowing one request at a time) is only skipped briefly. */
    static final java.time.Duration BUSY_PAUSE = java.time.Duration.ofSeconds(30);

    /** One model at one provider; models are asked in order, skipping any that are paused. */
    record Target(String label, RestClient client, String apiKey, String model) {
    }

    private final java.util.List<Target> targets;
    private final java.util.Map<String, java.time.Instant> pausedUntil = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.time.Clock clock;
    private final ObjectMapper json;

    /**
     * The main provider takes a comma-separated list of models (free quotas are per model, so the next
     * one takes over when one runs out); an optional fallback provider is asked after all of them.
     */
    @Autowired
    public OpenAiCompatibleClient(@Value("${app.ai.base-url}") String baseUrl,
                                  @Value("${app.ai.api-key}") String apiKey,
                                  @Value("${app.ai.model}") String model,
                                  @Value("${app.ai.fallback.base-url:}") String fallbackBaseUrl,
                                  @Value("${app.ai.fallback.api-key:}") String fallbackApiKey,
                                  @Value("${app.ai.fallback.model:}") String fallbackModel,
                                  RestClient.Builder builder, ObjectMapper json) {
        this(java.util.stream.Stream.concat(targets(baseUrl, apiKey, model, builder),
                targets(fallbackBaseUrl, fallbackApiKey, fallbackModel, builder)).toList(),
            java.time.Clock.systemUTC(), json);
    }

    OpenAiCompatibleClient(String apiKey, String model, RestClient client, ObjectMapper json) {
        this(java.util.List.of(new Target(model, client, apiKey, model)), java.time.Clock.systemUTC(), json);
    }

    OpenAiCompatibleClient(java.util.List<Target> targets, java.time.Clock clock, ObjectMapper json) {
        this.targets = targets.stream().filter(target -> target.apiKey() != null && !target.apiKey().isBlank()
            && target.model() != null && !target.model().isBlank()).toList();
        this.clock = clock;
        this.json = json;
    }

    private static java.util.stream.Stream<Target> targets(String baseUrl, String apiKey, String models,
                                                           RestClient.Builder builder) {
        if (baseUrl == null || baseUrl.isBlank() || apiKey == null || apiKey.isBlank() || models == null) {
            return java.util.stream.Stream.empty();
        }
        RestClient client = configuredClient(baseUrl.trim(), builder.clone());
        String host = java.net.URI.create(baseUrl.trim()).getHost();
        return java.util.Arrays.stream(models.split(",")).map(String::trim).filter(name -> !name.isEmpty())
            .map(name -> new Target(host + "/" + name, client, apiKey.trim(), name));
    }

    private static RestClient configuredClient(String baseUrl, RestClient.Builder builder) {
        SimpleClientHttpRequestFactory requests = new SimpleClientHttpRequestFactory();
        requests.setConnectTimeout(java.time.Duration.ofSeconds(5));
        requests.setReadTimeout(java.time.Duration.ofSeconds(30));
        return builder.requestFactory(requests).baseUrl(baseUrl).build();
    }

    public boolean configured() {
        return !targets.isEmpty();
    }

    /** The model's reply asked for as one JSON object (it is parsed by the caller). */
    public String completeJson(ArrayNode messages) {
        return complete(messages, true);
    }

    /** The model's reply as plain text. */
    public String completeText(ArrayNode messages) {
        return complete(messages, false);
    }

    /** Asks the first model that is not paused; a refusal or failure moves on to the next one. */
    private String complete(ArrayNode messages, boolean asJson) {
        java.time.Instant now = clock.instant();
        RuntimeException last = null;
        for (Target target : targets) {
            java.time.Instant until = pausedUntil.get(target.label());
            if (until != null && now.isBefore(until)) continue;
            try {
                String answer = complete(target, messages, asJson);
                pausedUntil.remove(target.label());
                return answer;
            } catch (RuntimeException failure) {
                // A request the provider finds malformed (400) says nothing about the model, and every
                // other model would refuse it too: no pause, no next model.
                if (failure instanceof org.springframework.web.client.RestClientResponseException response
                    && response.getStatusCode().value() == 400) {
                    throw failure;
                }
                last = failure;
                java.time.Duration pause = pauseAfter(failure);
                pausedUntil.put(target.label(), now.plus(pause));
                log.warn("LLM {} skipped for {} s ({}); trying the next one", target.label(),
                    pause.toSeconds(), failure.getMessage());
            }
        }
        throw last != null ? last : new IllegalStateException("Every configured LLM is paused");
    }

    // Used-up free quota (403 AllocationQuota.FreeTierOnly), refused key (401) or payment (402) last;
    // being busy (429) passes in seconds; anything else (timeouts, 5xx) in about a minute.
    static java.time.Duration pauseAfter(RuntimeException failure) {
        if (!(failure instanceof org.springframework.web.client.RestClientResponseException response)) return ERROR_PAUSE;
        int status = response.getStatusCode().value();
        if (status == 429) return BUSY_PAUSE;
        return java.util.Set.of(401, 402, 403).contains(status) ? QUOTA_PAUSE : ERROR_PAUSE;
    }

    private String complete(Target target, ArrayNode messages, boolean asJson) {
        ObjectNode body = json.createObjectNode();
        body.put("model", target.model());
        body.put("temperature", 0.1);
        body.set("messages", messages);
        if (asJson) body.set("response_format", json.createObjectNode().put("type", "json_object"));
        JsonNode response = target.client().post().uri("/chat/completions")
            .contentType(MediaType.APPLICATION_JSON)
            .header("Authorization", "Bearer " + target.apiKey())
            .body(body).retrieve().body(JsonNode.class);
        JsonNode message = response == null ? null : response.path("choices").path(0).path("message");
        if (message == null || message.isMissingNode()) throw new IllegalStateException("LLM returned no message");
        return message.path("content").isNull() ? "" : message.path("content").asText();
    }
}
