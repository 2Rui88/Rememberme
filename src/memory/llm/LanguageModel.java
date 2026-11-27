package memory.llm;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import memory.model.Message;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;

@Component
public class LanguageModel {
    private final RestClient client;
    private final String model;

    public LanguageModel(RestClient.Builder rest,
                         @Value("${memory.llm.base-url:http://localhost:11434/v1}") String baseUrl,
                         @Value("${memory.llm.api-key:}") String apiKey,
                         @Value("${memory.llm.model:}") String model,
                         @Value("${memory.llm.timeout:45s}") Duration timeout) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
        factory.setReadTimeout(timeout);
        RestClient.Builder builder = rest.clone().baseUrl(baseUrl.replaceAll("/+$", ""))
                .requestFactory(factory);
        if (!apiKey.isBlank()) {
            builder.defaultHeaders(headers -> headers.setBearerAuth(apiKey));
        }
        client = builder.build();
        this.model = model;
    }

    public boolean configured() {
        return !model.isBlank();
    }

    public String complete(String system, String input) {
        return complete(List.of(new Message(Message.Role.SYSTEM, system), new Message(Message.Role.USER, input)));
    }

    public String complete(List<Message> messages) {
        if (!configured()) {
            throw new IllegalStateException("MEMORY_LLM_MODEL must be configured for model calls");
        }
        JsonNode body = client.post().uri("/chat/completions")
                .body(Map.of("model", model, "messages", messages.stream().map(LanguageModel::toWire).toList()))
                .retrieve().body(JsonNode.class);
        String answer = body == null ? "" : body.path("choices").path(0).path("message").path("content").asText("");
        if (answer.isBlank()) {
            throw new IllegalStateException("Model returned no text");
        }
        return answer;
    }

    private static Map<String, Object> toWire(Message message) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("role", message.role().wireValue());
        fields.put("content", message.content());
        if (message.toolCallId() != null) {
            fields.put("tool_call_id", message.toolCallId());
        }
        if (!message.toolCalls().isEmpty()) {
            List<Map<String, Object>> calls = new ArrayList<>();
            for (Message.ToolCall call : message.toolCalls()) {
                calls.add(Map.of("id", call.id(), "type", "function",
                        "function", Map.of("name", call.name(), "arguments", call.arguments())));
            }
            fields.put("tool_calls", calls);
        }
        return fields;
    }
}
