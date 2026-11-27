package memory.longterm;

import memory.model.ConversationKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import static memory.longterm.MemoryProvider.Kind;

@Service
public class AgentMemorySettings {
    private final JdbcTemplate jdbc;
    private final Options defaults;

    public AgentMemorySettings(JdbcTemplate jdbc,
                               @Value("${memory.long.enabled:false}") boolean enabled,
                               @Value("${memory.long.provider:MEMOS}") Kind provider,
                               @Value("${memory.short.context-rounds:20}") int contextRounds) {
        this.jdbc = jdbc;
        defaults = new Options(enabled, provider, contextRounds, true);
    }

    public Options get(ConversationKey key) {
        return jdbc.query("""
                SELECT enabled, provider, context_rounds, filter_sensitive
                FROM agent_memory_settings WHERE tenant_id = ? AND agent_id = ?
                """, (rs, row) -> new Options(rs.getBoolean(1),
                rs.getString(2) == null ? Kind.MYSQL : Kind.valueOf(rs.getString(2)),
                rs.getInt(3), rs.getBoolean(4)), key.tenant(), key.agent()).stream().findFirst().orElse(defaults);
    }

    public Options update(ConversationKey key, Options options) {
        jdbc.update("""
                INSERT INTO agent_memory_settings VALUES (?, ?, ?, ?, ?, ?) AS incoming
                ON DUPLICATE KEY UPDATE enabled = incoming.enabled, provider = incoming.provider,
                    context_rounds = incoming.context_rounds, filter_sensitive = incoming.filter_sensitive
                """, key.tenant(), key.agent(), options.enabled(),
                options.provider() == null ? null : options.provider().name(),
                options.contextRounds(), options.filterSensitive());
        return get(key);
    }

    public record Options(boolean enabled, Kind provider, int contextRounds, boolean filterSensitive) {
        public Options {
            if (contextRounds < 0 || contextRounds > 200) {
                throw new IllegalArgumentException("contextRounds must be between 0 and 200");
            }
        }
    }
}
