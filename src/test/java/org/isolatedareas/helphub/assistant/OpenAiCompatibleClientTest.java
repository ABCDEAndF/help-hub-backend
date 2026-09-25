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
}
