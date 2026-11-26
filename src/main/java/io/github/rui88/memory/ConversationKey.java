package io.github.rui88.memory;

import java.util.regex.Pattern;

public record ConversationKey(String tenant, String user, String agent, String session) {
    private static final Pattern ID = Pattern.compile("[a-zA-Z0-9_-]{1,64}");

    public ConversationKey {
        requireId(tenant);
        requireId(user);
        requireId(agent);
        requireId(session);
    }

    private static void requireId(String id) {
        if (id == null || !ID.matcher(id).matches()) {
            throw new IllegalArgumentException("IDs must contain 1–64 ASCII letters, numbers, underscores or hyphens");
        }
    }

    public String value() {
        return tenant + "/" + user + "/" + agent + "/" + session;
    }
}
