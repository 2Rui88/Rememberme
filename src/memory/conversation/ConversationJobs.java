package memory.conversation;

import static memory.conversation.ConversationSummarizer.MIN_UNCOVERED;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import memory.longterm.LongMemory;
import memory.model.ConversationKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
class ConversationJobs {
    private static final String SUMMARY_READY = "c.last_sequence - c.summary_through >= ?";
    private static final String MEMORY_READY = """
            c.last_sequence > c.memory_through AND (
                c.memos_write_plan IS NOT NULL
                OR c.memory_requested_through > c.memory_through
                OR c.completed_turns - c.memory_turns >= ?
                OR (c.last_activity_at <= TIMESTAMPADD(SECOND, -?, UTC_TIMESTAMP(6)) AND NOT EXISTS (
                    SELECT 1 FROM conversation_active_turns t
                    WHERE t.session_key = c.session_key AND t.expires_at > UTC_TIMESTAMP(6)
                ))
            )
            """;
    private static final long CLAIM_SECONDS = Duration.ofMinutes(10).toSeconds();
    private static final int DISCOVERY_LIMIT = 16;

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final Duration idleTime;
    private final int batchTurns;
    private final boolean defaultLongEnabled;
    private final Duration activeTurnLease;

    ConversationJobs(JdbcTemplate jdbc, ObjectMapper json, @Value("${memory.long.idle-time:10m}") Duration idleTime,
                     @Value("${memory.long.batch-turns:10}") int batchTurns,
                     @Value("${memory.long.enabled:false}") boolean defaultLongEnabled,
                     @Value("${memory.llm.timeout:45s}") Duration modelTimeout) {
        if (idleTime.toSeconds() < 1 || batchTurns < 1) {
            throw new IllegalArgumentException("Memory idle time and batch size must be positive");
        }
        this.jdbc = jdbc;
        this.json = json;
        this.idleTime = idleTime;
        this.batchTurns = batchTurns;
        this.defaultLongEnabled = defaultLongEnabled;
        activeTurnLease = modelTimeout.plusMinutes(2);
    }

    String startTurn(ConversationKey key) {
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO conversation_active_turns (session_key, turn_id, expires_at)
                VALUES (?, ?, TIMESTAMPADD(SECOND, ?, UTC_TIMESTAMP(6)))
                """, key.value(), id, activeTurnLease.toSeconds());
        return id;
    }

    void finishTurn(ConversationKey key, String id) {
        jdbc.update("DELETE FROM conversation_active_turns WHERE session_key = ? AND turn_id = ?", key.value(), id);
    }

    void expireTurns() {
        jdbc.update("DELETE FROM conversation_active_turns WHERE expires_at <= UTC_TIMESTAMP(6) LIMIT 200");
    }

    void requestMemory(ConversationKey key, long through) {
        jdbc.update("""
                UPDATE conversations
                SET memory_requested_through = GREATEST(memory_requested_through, ?)
                WHERE session_key = ?
                """, through, key.value());
    }

    List<ConversationKey> ready(Kind kind) {
        if (kind == Kind.SUMMARY) {
            return jdbc.query("""
                    SELECT c.session_key
                    FROM conversations c
                    WHERE (c.summary_check_after IS NULL OR c.summary_check_after <= UTC_TIMESTAMP(6))
                        AND %s
                    ORDER BY c.summary_check_after, c.last_activity_at
                    LIMIT ?
                    """.formatted(SUMMARY_READY), (rs, row) -> ConversationKey.fromValue(rs.getString(1)),
                    MIN_UNCOVERED, DISCOVERY_LIMIT);
        }
        return jdbc.query("""
                SELECT c.session_key
                FROM conversations c
                LEFT JOIN agent_memory_settings s
                    ON s.tenant_id = SUBSTRING_INDEX(c.session_key, '/', 1)
                    AND s.agent_id = SUBSTRING_INDEX(SUBSTRING_INDEX(c.session_key, '/', 3), '/', -1)
                WHERE COALESCE(s.enabled, ?)
                    AND (c.memory_check_after IS NULL OR c.memory_check_after <= UTC_TIMESTAMP(6))
                    AND %s
                ORDER BY c.memory_check_after, c.last_activity_at
                LIMIT ?
                """.formatted(MEMORY_READY), (rs, row) -> ConversationKey.fromValue(rs.getString(1)), defaultLongEnabled,
                batchTurns, idleTime.toSeconds(), DISCOVERY_LIMIT);
    }

    @Transactional
    public Snapshot claim(Kind kind, ConversationKey key, String owner) {
        String prefix = kind.columnPrefix;
        String statement = """
                UPDATE conversations c
                SET %1$s_claim = ?, %1$s_check_after = TIMESTAMPADD(SECOND, ?, UTC_TIMESTAMP(6))
                WHERE c.session_key = ?
                    AND (c.%1$s_check_after IS NULL OR c.%1$s_check_after <= UTC_TIMESTAMP(6))
                    AND %2$s
                """.formatted(prefix, kind == Kind.SUMMARY ? SUMMARY_READY : MEMORY_READY);
        int updated = kind == Kind.SUMMARY
                ? jdbc.update(statement, owner, CLAIM_SECONDS, key.value(), MIN_UNCOVERED)
                : jdbc.update(statement, owner, CLAIM_SECONDS, key.value(), batchTurns, idleTime.toSeconds());
        if (updated != 1) {
            return null;
        }
        return jdbc.queryForObject("""
                SELECT last_sequence, completed_turns, memory_through, %s FROM conversations WHERE session_key = ?
                """.formatted(kind == Kind.LONG_MEMORY ? "memos_write_plan" : "NULL"), (rs, row) -> {
            String stored = rs.getString(4);
            MemosPlan plan = stored == null ? null : readMemosPlan(stored);
            long through = plan == null ? rs.getLong(1) : plan.through();
            long turns = plan == null ? rs.getLong(2) : plan.turns();
            return new Snapshot(key, through, turns, rs.getLong(3), plan);
        }, key.value());
    }

    MemosPlan saveMemosPlan(Snapshot snapshot, String owner, List<LongMemory.MemosWrite> writes) {
        MemosPlan plan = new MemosPlan(snapshot.through(), snapshot.turns(), writes);
        String encoded;
        try {
            encoded = json.writeValueAsString(plan);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Cannot encode MemOS write plan", error);
        }
        requireClaim(jdbc.update("""
                UPDATE conversations SET memos_write_plan = ?
                WHERE session_key = ? AND memory_claim = ? AND memory_check_after > UTC_TIMESTAMP(6)
                    AND memos_write_plan IS NULL
                """, encoded, snapshot.key().value(), owner));
        return plan;
    }

    void renewMemoryClaim(ConversationKey key, String owner) {
        requireClaim(jdbc.update("""
                UPDATE conversations SET memory_check_after = TIMESTAMPADD(SECOND, ?, UTC_TIMESTAMP(6))
                WHERE session_key = ? AND memory_claim = ? AND memory_check_after > UTC_TIMESTAMP(6)
                """, CLAIM_SECONDS, key.value(), owner));
    }

    void confirmMemosWrite(ConversationKey key, String owner, int index) {
        requireClaim(jdbc.update("""
                UPDATE conversations SET memos_write_plan = JSON_SET(memos_write_plan, ?, CAST('true' AS JSON))
                WHERE session_key = ? AND memory_claim = ? AND memory_check_after > UTC_TIMESTAMP(6)
                    AND memos_write_plan IS NOT NULL
                """, "$.writes[" + index + "].confirmed", key.value(), owner));
    }

    void completeMemory(Snapshot snapshot, String owner) {
        requireClaim(jdbc.update("""
                UPDATE conversations
                SET memory_through = ?, memory_turns = ?, memory_claim = NULL, memory_check_after = NULL,
                    memos_write_plan = NULL
                WHERE session_key = ? AND memory_claim = ?
                """, snapshot.through(), snapshot.turns(), snapshot.key().value(), owner));
    }

    void release(Kind kind, ConversationKey key, String owner, boolean retry) {
        String prefix = kind.columnPrefix;
        String nextCheck = retry ? "TIMESTAMPADD(SECOND, 30, UTC_TIMESTAMP(6))" : "NULL";
        jdbc.update("""
                UPDATE conversations SET %1$s_claim = NULL, %1$s_check_after = %2$s
                WHERE session_key = ? AND %1$s_claim = ?
                """.formatted(prefix, nextCheck), key.value(), owner);
    }

    private MemosPlan readMemosPlan(String encoded) {
        try {
            return json.readValue(encoded, MemosPlan.class);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Cannot decode MemOS write plan", error);
        }
    }

    private static void requireClaim(int updated) {
        if (updated != 1) {
            throw new IllegalStateException("Conversation memory claim was lost");
        }
    }

    enum Kind {
        SUMMARY("summary"), LONG_MEMORY("memory");

        private final String columnPrefix;

        Kind(String columnPrefix) {
            this.columnPrefix = columnPrefix;
        }
    }

    record Snapshot(ConversationKey key, long through, long turns, long memoryThrough, MemosPlan memosPlan) {}

    record MemosPlan(long through, long turns, List<LongMemory.MemosWrite> writes) {}
}
