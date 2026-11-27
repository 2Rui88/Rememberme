package io.github.rui88.memory.longterm;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.rui88.memory.model.ConversationKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class Mem0MemoryProvider implements MemoryProvider {
    private final RestClient reads;
    private final RestClient writes;
    private final boolean configured;

    public Mem0MemoryProvider(RestClient.Builder builder,
                              @Value("${memory.mem0.base-url:https://api.mem0.ai}") String baseUrl,
                              @Value("${memory.mem0.api-key:}") String apiKey) {
        configured = !apiKey.isBlank();
        String auth = configured ? "Token " + apiKey : "";
        reads = ProviderHttp.client(builder, baseUrl, auth, Duration.ofSeconds(5));
        writes = ProviderHttp.client(builder, baseUrl, auth, Duration.ofSeconds(30));
    }

    @Override
    public Kind kind() {
        return Kind.MEM0;
    }

    @Override
    public List<Hit> search(ConversationKey key, String query, Set<Scope> scopes) {
        requireConfiguration();
        List<Hit> hits = new ArrayList<>();
        for (Scope scope : scopes) {
            String scopedUser = scope.cube(key);
            for (JsonNode item : results(ProviderHttp.post(reads, "/v3/memories/search/",
                    Map.of("query", query, "top_k", 20, "filters", Map.of("user_id", scopedUser))))) {
                String id = item.path("id").asText("");
                String text = item.path("memory").asText("");
                String resultOwner = item.path("user_id").asText(scopedUser);
                if (!id.isBlank() && !text.isBlank() && scopedUser.equals(resultOwner)) {
                    JsonNode score = item.path("score");
                    hits.add(new Hit(id, scope, text, score.isNumber() ? score.asDouble() : null));
                }
            }
        }
        return hits;
    }

    @Override
    public boolean add(ConversationKey key, Candidate candidate) {
        requireConfiguration();
        JsonNode response = ProviderHttp.post(writes, "/v3/memories/add/", Map.of(
                "user_id", candidate.scope().cube(key),
                "messages", List.of(Map.of("role", "user", "content", candidate.content())),
                "infer", false, "async_mode", false));
        for (JsonNode item : results(response)) {
            if (!item.path("id").asText("").isBlank()) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void delete(ConversationKey key, Scope scope, List<String> ids) {
        requireConfiguration();
        for (String id : ids) {
            writes.delete().uri("/v1/memories/{id}/", id).retrieve().toBodilessEntity();
        }
    }

    private static JsonNode results(JsonNode body) {
        return body.isArray() ? body : body.path("results");
    }

    private void requireConfiguration() {
        if (!configured) {
            throw new IllegalStateException("MEMORY_MEM0_API_KEY must be configured");
        }
    }
}
