package io.github.rui88.memory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class MysqlMemoryProvider implements MemoryProvider {
    private final JdbcTemplate jdbc;

    public MysqlMemoryProvider(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Kind kind() {
        return Kind.MYSQL;
    }

    @Override
    public List<Hit> search(ConversationKey key, String query, Set<Scope> scopes) {
        List<Hit> results = new ArrayList<>();
        for (Scope scope : scopes) {
            results.addAll(jdbc.query("""
                    SELECT id, content FROM long_memories WHERE owner_id = ? AND scope_key = ?
                    ORDER BY created_at DESC, id LIMIT 500
                    """, (rs, row) -> {
                        String text = rs.getString(2);
                        return new Hit(rs.getString(1), scope, text, MemoryText.relevance(query, text));
                    }, MemoryProvider.owner(key), scope.key(key)));
        }
        return results.stream().filter(hit -> hit.score() > 0)
                .sorted(Comparator.comparing(Hit::score).reversed()).limit(20).toList();
    }

    @Override
    public boolean add(ConversationKey key, Candidate candidate) {
        return jdbc.update("INSERT INTO long_memories (id, owner_id, scope_key, content) VALUES (?, ?, ?, ?)",
                UUID.randomUUID().toString(), MemoryProvider.owner(key), candidate.scope().key(key),
                candidate.content()) == 1;
    }

    @Override
    public void delete(ConversationKey key, Scope scope, List<String> ids) {
        jdbc.batchUpdate("DELETE FROM long_memories WHERE owner_id = ? AND scope_key = ? AND id = ?",
                ids, ids.size(), (statement, id) -> {
                    statement.setString(1, MemoryProvider.owner(key));
                    statement.setString(2, scope.key(key));
                    statement.setString(3, id);
                });
    }
}
