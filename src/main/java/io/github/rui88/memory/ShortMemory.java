package io.github.rui88.memory;

import static io.github.rui88.memory.Message.Role.ASSISTANT;
import static io.github.rui88.memory.Message.Role.SYSTEM;
import static io.github.rui88.memory.Message.Role.TOOL;
import static io.github.rui88.memory.Message.Role.USER;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

@Service
public class ShortMemory {
    private static final Logger log = LoggerFactory.getLogger(ShortMemory.class);
    private static final int MAX_CACHED = 200;
    private static final String CACHE_TTL_SECONDS = "604800";
    private static final int SUMMARY_BUDGET = 2000;
    private static final DefaultRedisScript<Long> UPDATE_LIST = new DefaultRedisScript<>("""
            local first = redis.call('LINDEX', KEYS[1], 0)
            local cached = first and cjson.decode(first).sequence or 0
            local revision = tonumber(ARGV[2])
            if ARGV[1] == 'append' then
                if cached ~= revision then return 0 end
            else
                if cached > revision then return 0 end
                redis.call('DEL', KEYS[1])
            end
            for i = 5, #ARGV do redis.call('LPUSH', KEYS[1], ARGV[i]) end
            redis.call('LTRIM', KEYS[1], 0, tonumber(ARGV[3]) - 1)
            redis.call('EXPIRE', KEYS[1], tonumber(ARGV[4]))
            return 1
            """, Long.class);
    private static final DefaultRedisScript<Long> UPDATE_SUMMARY = new DefaultRedisScript<>("""
            local old = redis.call('GET', KEYS[1])
            if old and cjson.decode(old).through > tonumber(ARGV[1]) then return 0 end
            redis.call('SET', KEYS[1], ARGV[2], 'EX', 3600)
            return 1
            """, Long.class);

    private final ConversationStore store;
    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final TextTokens tokens;
    private final int historyBudget;

    public ShortMemory(ConversationStore store, StringRedisTemplate redis, ObjectMapper json, TextTokens tokens,
                       @Value("${memory.short.history-tokens:64000}") int historyBudget) {
        if (historyBudget <= SUMMARY_BUDGET) {
            throw new IllegalArgumentException("Short history budget must exceed the summary budget");
        }
        this.store = store;
        this.redis = redis;
        this.json = json;
        this.tokens = tokens;
        this.historyBudget = historyBudget;
    }

    public List<Message.Stored> append(ConversationKey key, List<Message> messages) {
        List<Message.Stored> stored = store.append(key, messages);
        if (!stored.isEmpty()) {
            updateList(key, "append", stored.get(0).sequence() - 1, stored);
        }
        return stored;
    }

    public Window load(ConversationKey key, int contextRounds) {
        if (contextRounds <= 0) {
            return Window.EMPTY;
        }
        List<Message.Stored> messages = recent(key, Math.min(contextRounds, MAX_CACHED) * 3);
        int total = messages.stream().mapToInt(this::cost).sum();
        if (total <= historyBudget) {
            return new Window("", align(messages));
        }

        int remaining = historyBudget - SUMMARY_BUDGET;
        int first = messages.size();
        while (first > 0) {
            int cost = cost(messages.get(first - 1));
            if (cost > remaining) {
                break;
            }
            remaining -= cost;
            first--;
        }
        String digest = summary(key).text();
        return new Window(digest == null ? "" : tokens.truncate(digest, SUMMARY_BUDGET),
                align(messages.subList(first, messages.size())));
    }

    private List<Message.Stored> recent(ConversationKey key, int limit) {
        List<Message.Stored> cached = new ArrayList<>();
        try {
            List<String> rows = redis.opsForList().range(historyKey(key), 0, MAX_CACHED - 1);
            if (rows != null) {
                for (String row : rows) {
                    cached.add(json.readValue(row, Message.Stored.class));
                }
            }
        } catch (RuntimeException | JsonProcessingException error) {
            log.debug("Conversation cache read failed", error);
            cached.clear();
        }

        // A small MySQL head read prevents serving stale hits after failed cache writes.
        long head = store.head(key);
        int wanted = (int) Math.min(Math.min(limit, MAX_CACHED), head);
        if (wanted == 0) {
            return List.of();
        }
        if (cached.size() >= wanted && contiguous(cached, head, wanted)) {
            List<Message.Stored> window = new ArrayList<>(cached.subList(0, wanted));
            Collections.reverse(window);
            return window;
        }
        List<Message.Stored> history = store.recent(key, head, MAX_CACHED);
        updateList(key, "replace", head, history);
        return history.subList(Math.max(0, history.size() - wanted), history.size());
    }

    private static boolean contiguous(List<Message.Stored> history, long head, int count) {
        for (int i = 0; i < count; i++) {
            if (history.get(i).sequence() != head - i) {
                return false;
            }
        }
        return true;
    }

    private void updateList(ConversationKey key, String mode, long sequence, List<Message.Stored> entries) {
        try {
            List<String> args = new ArrayList<>(List.of(mode, Long.toString(sequence),
                    Integer.toString(MAX_CACHED), CACHE_TTL_SECONDS));
            for (Message.Stored entry : entries) {
                args.add(json.writeValueAsString(entry));
            }
            redis.execute(UPDATE_LIST, List.of(historyKey(key)), args.toArray());
        } catch (RuntimeException | JsonProcessingException error) {
            log.debug("Conversation cache update failed after durable write", error);
        }
    }

    ConversationStore.Summary summary(ConversationKey key) {
        try {
            String cached = redis.opsForValue().get(summaryKey(key));
            if (cached != null) {
                return json.readValue(cached, ConversationStore.Summary.class);
            }
        } catch (RuntimeException | JsonProcessingException error) {
            log.debug("Conversation summary cache read failed", error);
        }
        ConversationStore.Summary summary = store.summary(key);
        if (summary.text() != null) {
            cacheSummary(key, summary);
        }
        return summary;
    }

    void cacheSummary(ConversationKey key, ConversationStore.Summary summary) {
        try {
            redis.execute(UPDATE_SUMMARY, List.of(summaryKey(key)), Long.toString(summary.through()),
                    json.writeValueAsString(summary));
        } catch (RuntimeException | JsonProcessingException error) {
            log.debug("Conversation summary cache update failed", error);
        }
    }

    int cost(Message.Stored stored) {
        Message message = stored.message();
        int size = 4 + tokens.count(message.content());
        if (message.toolCallId() != null) {
            size += tokens.count(message.toolCallId());
        }
        for (Message.ToolCall call : message.toolCalls()) {
            size += tokens.count(call.id()) + tokens.count(call.name()) + tokens.count(call.arguments());
        }
        return size;
    }

    private static List<Message.Stored> align(List<Message.Stored> messages) {
        List<Message.Stored> aligned = new ArrayList<>();
        boolean started = false;
        for (int index = 0; index < messages.size(); index++) {
            Message.Stored entry = messages.get(index);
            Message message = entry.message();
            if (message.role() == USER) {
                started = true;
            }
            if (!(message.role() == SYSTEM || started && message.role() != TOOL)) {
                continue;
            }
            if (message.toolCalls().isEmpty()) {
                aligned.add(entry);
                continue;
            }
            Set<String> pending = new HashSet<>();
            message.toolCalls().forEach(call -> pending.add(call.id()));
            int next = index + 1;
            while (next < messages.size()) {
                Message result = messages.get(next).message();
                if (result.role() != TOOL || !pending.remove(result.toolCallId())) {
                    break;
                }
                next++;
            }
            if (pending.isEmpty()) {
                aligned.addAll(messages.subList(index, next));
            } else if (!message.content().isBlank()) {
                aligned.add(new Message.Stored(entry.sequence(), new Message(ASSISTANT, message.content())));
            }
            index = next - 1;
        }
        return List.copyOf(aligned);
    }

    private static String historyKey(ConversationKey key) {
        return "memory:history:" + key.value();
    }

    private static String summaryKey(ConversationKey key) {
        return "memory:summary:" + key.value();
    }

    public record Window(String summary, List<Message.Stored> messages) {
        public static final Window EMPTY = new Window("", List.of());
    }
}
