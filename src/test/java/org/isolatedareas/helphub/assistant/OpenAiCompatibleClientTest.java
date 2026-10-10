package org.isolatedareas.helphub.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class OpenAiCompatibleClientTest {

    @Test
    void sendsToolsAndParsesToolCallsFromAnOpenAiCompatibleProvider() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        ObjectMapper json = new ObjectMapper();
        OpenAiCompatibleClient client = new OpenAiCompatibleClient(
            "test-key", "test-model", builder.baseUrl("https://llm.example/v1").build(), json);

        server.expect(requestTo("https://llm.example/v1/chat/completions"))
            .andExpect(header("Authorization", "Bearer test-key"))
            .andExpect(content().contentType(MediaType.APPLICATION_JSON))
            .andExpect(content().json("""
                {"model":"test-model","tool_choice":"auto","tools":[
                  {"type":"function","function":{"name":"check_inventory"}},
                  {"type":"function","function":{"name":"get_request_status"}},
                  {"type":"function","function":{"name":"list_my_requests"}},
                  {"type":"function","function":{"name":"list_my_reservations"}},
                  {"type":"function","function":{"name":"list_service_points"}},
                  {"type":"function","function":{"name":"find_nearby_service_points"}},
                  {"type":"function","function":{"name":"prepare_supply_request"}},
                  {"type":"function","function":{"name":"reserve_pickup_slot"}}
                ]}
                """, false))
            .andRespond(withSuccess("""
                {"choices":[{"message":{"content":"",
                  "tool_calls":[{"id":"call-1","type":"function","function":{
                    "name":"check_inventory","arguments":"{\\"category\\":\\"FOOD\\"}"}}]}}]}
                """, MediaType.APPLICATION_JSON));

        OpenAiCompatibleClient.ModelMessage response = client.complete(json.createArrayNode()
            .add(json.createObjectNode().put("role", "user").put("content", "还有食物吗？")));

        assertThat(client.configured()).isTrue();
        assertThat(response.content()).isEmpty();
        assertThat(response.toolCalls()).hasSize(1);
        assertThat(response.toolCalls().path(0).path("function").path("name").asText())
            .isEqualTo("check_inventory");
        server.verify();
    }

    @Test
    void movesToTheNextModelWhenOnesFreeQuotaIsUsedUpAndPausesIt() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        ObjectMapper json = new ObjectMapper();
        RestClient client = builder.baseUrl("https://llm.example/v1").build();
        java.util.concurrent.atomic.AtomicReference<java.time.Instant> now =
            new java.util.concurrent.atomic.AtomicReference<>(java.time.Instant.parse("2026-10-10T00:00:00Z"));
        java.time.Clock clock = new java.time.Clock() {
            public java.time.ZoneId getZone() { return java.time.ZoneOffset.UTC; }
            public java.time.Clock withZone(java.time.ZoneId zone) { return this; }
            public java.time.Instant instant() { return now.get(); }
        };
        OpenAiCompatibleClient chain = new OpenAiCompatibleClient(java.util.List.of(
            new OpenAiCompatibleClient.Target("a", client, "key", "model-a"),
            new OpenAiCompatibleClient.Target("b", client, "key", "model-b")), clock, json);
        String answer = """
            {"choices":[{"message":{"content":"有的"}}]}
            """;

        server.expect(org.springframework.test.web.client.ExpectedCount.once(), requestTo("https://llm.example/v1/chat/completions"))
            .andExpect(content().json("{\"model\":\"model-a\"}", false))
            .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators
                .withStatus(org.springframework.http.HttpStatus.FORBIDDEN)
                .body("{\"code\":\"AllocationQuota.FreeTierOnly\"}").contentType(MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://llm.example/v1/chat/completions"))
            .andExpect(content().json("{\"model\":\"model-b\"}", false))
            .andRespond(withSuccess(answer, MediaType.APPLICATION_JSON));
        // While model-a is paused only model-b is asked.
        server.expect(requestTo("https://llm.example/v1/chat/completions"))
            .andExpect(content().json("{\"model\":\"model-b\"}", false))
            .andRespond(withSuccess(answer, MediaType.APPLICATION_JSON));
        // After the pause model-a is tried again first.
        server.expect(requestTo("https://llm.example/v1/chat/completions"))
            .andExpect(content().json("{\"model\":\"model-a\"}", false))
            .andRespond(withSuccess(answer, MediaType.APPLICATION_JSON));

        var messages = json.createArrayNode().add(json.createObjectNode().put("role", "user").put("content", "有米吗"));
        assertThat(chain.complete(messages).content()).isEqualTo("有的");
        now.set(now.get().plus(java.time.Duration.ofMinutes(10)));
        assertThat(chain.complete(messages).content()).isEqualTo("有的");
        now.set(now.get().plus(OpenAiCompatibleClient.QUOTA_PAUSE));
        assertThat(chain.complete(messages).content()).isEqualTo("有的");
        server.verify();
    }

    @Test
    void isNotConfiguredWithoutAKey() {
        ObjectMapper json = new ObjectMapper();
        OpenAiCompatibleClient none = new OpenAiCompatibleClient("", "qwen-plus", RestClient.builder().build(), json);
        assertThat(none.configured()).isFalse();
    }
}
