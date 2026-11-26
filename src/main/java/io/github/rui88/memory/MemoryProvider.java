package io.github.rui88.memory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

public interface MemoryProvider {
    Kind kind();

    List<Hit> search(ConversationKey key, String query, Set<Scope> scopes);

    boolean add(ConversationKey key, Candidate candidate);

    void delete(ConversationKey key, Scope scope, List<String> ids);

    enum Kind { MYSQL, MEMOS, MEM0 }

    enum Scope {
        USER, AGENT;

        public String key(ConversationKey conversation) {
            return this == USER ? "user_profile" : "agent_" + conversation.agent();
        }

        public String cube(ConversationKey conversation) {
            return "u_" + hash(owner(conversation)) + "_" + key(conversation);
        }
    }

    enum Category { FACT, PREFERENCE, CORRECTION, TASK, WORKFLOW }

    record Candidate(String content, Scope scope, Category category, int importance) {}

    record Hit(String id, Scope scope, String content, Double score) {}

    static String owner(ConversationKey key) {
        return key.tenant() + "/" + key.user();
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }
}
