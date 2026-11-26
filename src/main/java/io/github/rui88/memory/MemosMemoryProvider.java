package io.github.rui88.memory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

@Component
public class MemosMemoryProvider implements MemoryProvider {
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

    List<Hit> conflictCandidates(ConversationKey key, Scope scope) {
        requireOwner();
        String cube = scope.cube(key);
        ensureCube(reads, cube);
        Map<String, Hit> hits = new LinkedHashMap<>();
        int pageSize = 100;
        for (int page = 1; ; page++) {
            JsonNode result = product(reads, "/get_memory", Map.of(
                    "user_id", owner, "mem_cube_id", cube, "page", page, "page_size", pageSize,
                    "include_preference", true, "include_tool_memory", false, "include_skill_memory", false));
            if (!result.isObject()) {
                throw new IllegalStateException("MemOS did not return Cube memory data");
            }
            int total = 0;
            for (String type : List.of("text_mem", "pref_mem")) {
                for (JsonNode group : result.path(type)) {
                    if (!cube.equals(group.path("cube_id").asText())) {
                        continue;
                    }
                    total = Math.max(total, group.path("total_nodes").asInt());
                    for (JsonNode record : group.path("memories")) {
                        String id = id(record);
                        String text = content(record);
                        if (!id.isBlank() && !text.isBlank()) {
                            hits.putIfAbsent(id, new Hit(id, scope, text, null));
                        }
                    }
                }
            }
            if ((long) page * pageSize >= total) {
                return new ArrayList<>(hits.values());
            }
        }
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
        List<String> added = new ArrayList<>();
        if (!result.isArray()) {
            return added;
        }
        for (JsonNode record : result) {
            String id = id(record);
            String returnedCube = record.path("cube_id").asText(cube);
            if (!id.isBlank() && cube.equals(returnedCube) && !added.contains(id)) {
                added.add(id);
            }
        }
        return added;
    }

    Set<String> confirmedContents(List<String> ids) {
        JsonNode result = product(reads, "/get_memory_by_ids", ids);
        Set<String> contents = new HashSet<>();
        for (JsonNode record : result.path("memories")) {
            if (ids.contains(id(record))) {
                String text = content(record);
                if (!text.isBlank()) {
                    contents.add(MemoryText.normalized(text));
                }
            }
        }
        return contents;
    }

    private static String id(JsonNode record) {
        return record.path("memory_id").asText(record.path("id").asText(""));
    }

    private static String content(JsonNode record) {
        return record.path("memory").asText(record.path("content").asText(""));
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
