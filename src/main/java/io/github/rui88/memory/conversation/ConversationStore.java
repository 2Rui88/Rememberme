package io.github.rui88.memory.conversation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.rui88.memory.model.ConversationKey;
import io.github.rui88.memory.model.Message;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class ConversationStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public ConversationStore(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Transactional
    public List<Message.Stored> append(ConversationKey key, List<Message> messages) {
        if (messages.isEmpty()) {
            return List.of();
        }
        jdbc.update("""
                INSERT INTO conversations (session_key) VALUES (?)
                ON DUPLICATE KEY UPDATE last_sequence = last_sequence
                """, key.value());
        TurnProgress progress = jdbc.queryForObject("""
                SELECT last_sequence, completed_turns, open_turn
                FROM conversations WHERE session_key = ? FOR UPDATE
                """, (rs, row) -> new TurnProgress(rs.getLong(1), rs.getLong(2), rs.getBoolean(3)), key.value());
        long sequence = progress.sequence();
        long completedTurns = progress.completed();
        boolean openTurn = progress.open();
        List<Message.Stored> stored = new ArrayList<>(messages.size());
        for (Message message : messages) {
            stored.add(new Message.Stored(++sequence, message));
            if (message.role() == Message.Role.USER) {
                openTurn = true;
            } else if (openTurn && message.role() == Message.Role.ASSISTANT && message.toolCalls().isEmpty()) {
                completedTurns++;
                openTurn = false;
            }
        }
        jdbc.batchUpdate("INSERT INTO conversation_messages VALUES (?, ?, ?)", stored, stored.size(),
                (statement, entry) -> {
                    statement.setString(1, key.value());
                    statement.setLong(2, entry.sequence());
                    statement.setString(3, encode(entry.message()));
                });
        jdbc.update("""
                UPDATE conversations SET last_sequence = ?, completed_turns = ?, open_turn = ?,
                    last_activity_at = UTC_TIMESTAMP(6)
                WHERE session_key = ?
                """, sequence, completedTurns, openTurn, key.value());
        return stored;
    }

    public long head(ConversationKey key) {
        return jdbc.query("SELECT last_sequence FROM conversations WHERE session_key = ?",
                (rs, row) -> rs.getLong(1), key.value()).stream().findFirst().orElse(0L);
    }

    public List<Message.Stored> recent(ConversationKey key, int limit) {
        List<Message.Stored> messages = jdbc.query("""
                SELECT sequence_no, payload FROM conversation_messages
                WHERE session_key = ? ORDER BY sequence_no DESC LIMIT ?
                """, (rs, row) -> new Message.Stored(rs.getLong(1), decode(rs.getString(2))),
                key.value(), limit);
        Collections.reverse(messages);
        return messages;
    }

    public List<Message.Stored> range(ConversationKey key, long after, long through, int limit) {
        return jdbc.query("""
                SELECT sequence_no, payload FROM conversation_messages
                WHERE session_key = ? AND sequence_no > ? AND sequence_no <= ?
                ORDER BY sequence_no LIMIT ?
                """, (rs, row) -> new Message.Stored(rs.getLong(1), decode(rs.getString(2))),
                key.value(), after, through, limit);
    }

    public Summary summary(ConversationKey key) {
        return jdbc.query("SELECT summary_through, summary FROM conversations WHERE session_key = ?",
                (rs, row) -> new Summary(rs.getLong(1), rs.getString(2)), key.value())
                .stream().findFirst().orElse(Summary.EMPTY);
    }

    public boolean summarizeThrough(ConversationKey key, long expected, Summary next) {
        return jdbc.update("""
                UPDATE conversations SET summary_through = ?, summary = ?
                WHERE session_key = ? AND summary_through = ? AND last_sequence >= ?
                """, next.through(), next.text(), key.value(), expected, next.through()) == 1;
    }

    private String encode(Message message) {
        try {
            return json.writeValueAsString(message);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Cannot encode conversation message", error);
        }
    }

    private Message decode(String value) {
        try {
            return json.readValue(value, Message.class);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Cannot decode conversation message", error);
        }
    }

    public record Summary(long through, String text) {
        static final Summary EMPTY = new Summary(0, null);
    }

    private record TurnProgress(long sequence, long completed, boolean open) {}
}
