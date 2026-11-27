package memory.longterm;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.JsonNode;
import memory.model.ConversationKey;
import memory.model.Message;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

@Component
public class MemosMemoryProvider implements MemoryProvider {
    private static final int FEEDBACK_CONTEXT_MESSAGES = 12;
    private static final int FEEDBACK_MESSAGE_CHARACTERS = 1500;

    private final RestClient reads;
    private final RestClient writes;
    private final String owner;
    private final String addMode;
    private final Set<String> knownCubes = ConcurrentHashMap.newKeySet();

    public MemosMemoryProvider(RestClient.Builder builder,
                               @Value("${memory.memos.base-url:http://localhost:8000/product}") String baseUrl,
                               @Value("${memory.memos.authorization:}") String authorization,
                               @Value("${memory.memos.owner-id:}") String owner,
                               @Value("${memory.memos.add-mode:async}") String addMode) {
        reads = ProviderHttp.client(builder, baseUrl, authorization, Duration.ofSeconds(5));
        writes = ProviderHttp.client(builder, baseUrl, authorization, Duration.ofSeconds(45));
        this.owner = owner;
        if (!Set.of("sync", "async").contains(addMode)) {
            throw new IllegalArgumentException("MemOS add mode must be sync or async");
        }
        this.addMode = addMode;
    }

    @Override
    public Kind kind() {
        return Kind.MEMOS;
    }

    @Override
    public List<Hit> search(ConversationKey key, String query, Set<Scope> scopes) {
        requireOwner();
        Map<String, Scope> scopeByCube = scopes.stream().collect(Collectors.toMap(scope -> scope.cube(key), scope -> scope));
        for (String cube : scopeByCube.keySet()) {
            ensureCube(reads, cube);
        }
        JsonNode result = product(reads, "/search", Map.of(
                "user_id", owner,
                "query", query,
                "readable_cube_ids", scopeByCube.keySet(),
                "mode", "fast",
                "top_k", 20,
                "relativity", .45,
                "dedup", "mmr",
                "include_preference", true,
                "pref_top_k", 6));
        Map<String, Hit> hits = new LinkedHashMap<>();
        for (String kind : List.of("text_mem", "pref_mem", "skill_mem", "tool_mem")) {
            for (JsonNode group : result.path(kind)) {
                Scope scope = scopeByCube.get(group.path("cube_id").asText());
                if (scope == null) {
                    continue;
                }
                for (JsonNode memory : group.path("memories")) {
                    String id = memory.path("id").asText(memory.path("memory_id").asText(""));
                    String text = memory.path("memory").asText(memory.path("content").asText(""));
                    if (!id.isBlank() && !text.isBlank()) {
                        JsonNode score = memory.path("score");
                        if (!score.isNumber()) {
                            score = memory.path("metadata").path("relativity");
                        }
                        hits.putIfAbsent(group.path("cube_id").asText() + ":" + id,
                                new Hit(id, scope, text, score.isNumber() ? score.asDouble() : null));
                    }
                }
            }
        }
        return new ArrayList<>(hits.values());
    }

    @Override
    public boolean add(ConversationKey key, Candidate candidate) {
        return !addBatch(key, candidate.scope(), List.of(candidate)).isEmpty();
    }

    List<String> addBatch(ConversationKey key, Scope scope, List<Candidate> candidates) {
        requireOwner();
        String cube = scope.cube(key);
        ensureCube(writes, cube);
        Map<String, Object> request = new HashMap<>();
        request.put("user_id", owner);
        request.put("session_id", key.value());
        request.put("writable_cube_ids", List.of(cube));
        request.put("async_mode", addMode);
        request.put("mode", "fine");
        request.put("messages", candidates.stream()
                .map(candidate -> Map.of("role", "user", "content", candidate.content()))
                .toList());
        if (candidates.size() == 1) {
            Candidate candidate = candidates.get(0);
            request.put("info", Map.of("category", candidate.category().name(), "importance", candidate.importance()));
        } else {
            request.put("info", Map.of("candidates", candidates.stream()
                    .map(candidate -> Map.of("category", candidate.category().name(),
                            "importance", candidate.importance()))
                    .toList()));
        }
        JsonNode result = product(writes, "/add", request);
        if (!result.isArray()) {
            throw new IllegalStateException("MemOS did not return a valid add response");
        }
        List<String> added = new ArrayList<>();
        for (JsonNode record : result) {
            String id = id(record);
            String returnedCube = record.path("cube_id").asText(cube);
            if (!id.isBlank() && cube.equals(returnedCube) && !added.contains(id)) {
                added.add(id);
            }
        }
        return added;
    }

    void feedback(ConversationKey key, Candidate correction, List<Message.Stored> history) {
        requireOwner();
        String cube = correction.scope().cube(key);
        ensureCube(writes, cube);
        Map<String, Object> request = new HashMap<>();
        request.put("user_id", owner);
        request.put("session_id", key.value());
        request.put("writable_cube_ids", List.of(cube));
        request.put("feedback_content", correction.content());
        request.put("history", feedbackHistory(history, correction.sourceSequence()));
        request.put("async_mode", "sync");
        request.put("corrected_answer", false);
        JsonNode result = product(writes, "/feedback", request);
        for (JsonNode item : result) {
            JsonNode record = item.path("record");
            if (record.path("add").size() > 0 || record.path("update").size() > 0) {
                return;
            }
        }
        throw new IllegalStateException("MemOS did not confirm a memory correction");
    }

    private static List<Map<String, String>> feedbackHistory(List<Message.Stored> history, long sourceSequence) {
        List<Message> recent = new ArrayList<>();
        for (int index = history.size() - 1; index >= 0 && recent.size() < FEEDBACK_CONTEXT_MESSAGES; index--) {
            Message.Stored entry = history.get(index);
            Message message = entry.message();
            if ((sourceSequence == 0 || entry.sequence() <= sourceSequence)
                    && (message.role() == Message.Role.USER || message.role() == Message.Role.ASSISTANT)
                    && !message.content().isBlank() && !MemoryJudge.sensitive(message.content())) {
                recent.add(message);
            }
        }
        Collections.reverse(recent);
        return recent.stream().map(message -> Map.of("role", message.role().wireValue(),
                "content", MemoryText.limit(message.content(), FEEDBACK_MESSAGE_CHARACTERS))).toList();
    }

    private static String id(JsonNode record) {
        return record.path("memory_id").asText(record.path("id").asText(""));
    }

    @Override
    public void delete(ConversationKey key, Scope scope, List<String> ids) {
        requireOwner();
        // The API treats user_id as a delete-by-filter selector, which is exclusive with memory_ids.
        JsonNode result = product(writes, "/delete_memory", Map.of(
                "writable_cube_ids", List.of(scope.cube(key)), "memory_ids", ids));
        if ((result.has("success") && !result.path("success").asBoolean())
                || "failure".equalsIgnoreCase(result.path("status").asText())) {
            throw new IllegalStateException("MemOS did not confirm memory deletion");
        }
    }

    private void ensureCube(RestClient client, String cube) {
        if (knownCubes.contains(cube)) {
            return;
        }
        try {
            product(client, "/create_cube", Map.of("cube_id", cube, "cube_name", cube, "owner_id", owner));
        } catch (HttpClientErrorException error) {
            if (!error.getResponseBodyAsString().toLowerCase(Locale.ROOT).contains("already exists")) {
                throw error;
            }
        }
        knownCubes.add(cube);
    }

    private static JsonNode product(RestClient client, String endpoint, Object request) {
        JsonNode response = ProviderHttp.post(client, endpoint, request);
        int code = response.path("code").asInt(-1);
        if (code != 0 && code != 200) {
            throw new IllegalStateException("MemOS operation failed: " + endpoint + " (" + code + ")");
        }
        return response.path("data");
    }

    private void requireOwner() {
        if (owner.isBlank()) {
            throw new IllegalStateException("MEMORY_MEMOS_OWNER_ID must name a registered MemOS user");
        }
    }
}
