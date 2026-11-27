package io.github.rui88.memory.conversation;

import static io.github.rui88.memory.model.Message.Role.ASSISTANT;
import static io.github.rui88.memory.model.Message.Role.SYSTEM;
import static io.github.rui88.memory.model.Message.Role.TOOL;
import static io.github.rui88.memory.model.Message.Role.USER;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.rui88.memory.llm.TextTokens;
import io.github.rui88.memory.model.ConversationKey;
import io.github.rui88.memory.model.Message;
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
            local cached = 0
            if first then
                local ok, entry = pcall(cjson.decode, first)
                if ok and type(entry) == 'table' then cached = tonumber(entry.sequence) or 0 end
            end
            local revision = tonumber(ARGV[2])
            local newest = revision
            if ARGV[1] == 'append' then newest = revision + #ARGV - 4 end
            local known = tonumber(redis.call('GET', KEYS[2])) or 0
            if math.max(cached, known) > newest then return 0 end
            redis.call('SET', KEYS[2], newest, 'EX', tonumber(ARGV[4]))
            if ARGV[1] == 'append' then
                if cached >= newest then return 0 end
                if cached ~= revision then
                    redis.call('DEL', KEYS[1])
                    return 0
                end
            else
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

    public Window load(ConversationKey key) {
        List<Message.Stored> messages = align(recent(key));
        int total = messages.stream().mapToInt(this::cost).sum();
        if (total <= historyBudget) {
            return new Window("", messages);
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

    private List<Message.Stored> recent(ConversationKey key) {
        try {
            List<String> rows = redis.opsForList().range(historyKey(key), 0, -1);
            if (rows != null && !rows.isEmpty()) {
                List<Message.Stored> cached = new ArrayList<>(rows.size());
                for (String row : rows) {
                    cached.add(json.readValue(row, Message.Stored.class));
                }
                if (contiguous(cached)) {
                    Collections.reverse(cached);
                    return cached;
                }
            }
        } catch (RuntimeException | JsonProcessingException error) {
            log.debug("Conversation cache read failed", error);
        }
        List<Message.Stored> history = store.recent(key, MAX_CACHED);
        if (!history.isEmpty()) {
            updateList(key, "replace", history.get(history.size() - 1).sequence(), history);
        }
        return history;
    }

    private static boolean contiguous(List<Message.Stored> history) {
        long head = history.get(0).sequence();
        if (head < 1 || history.size() != Math.min(head, MAX_CACHED)) {
            return false;
        }
        for (int i = 0; i < history.size(); i++) {
            if (history.get(i).sequence() != head - i || history.get(i).message() == null) {
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
            redis.execute(UPDATE_LIST, List.of(historyKey(key), historyKey(key) + ":revision"), args.toArray());
        } catch (RuntimeException | JsonProcessingException error) {
            log.debug("Conversation cache update failed after durable write", error);
            if ("append".equals(mode)) {
                try {
                    redis.delete(historyKey(key));
                } catch (RuntimeException invalidationError) {
                    log.debug("Conversation cache invalidation failed", invalidationError);
                }
            }
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
        return "memory:history:{" + key.value() + "}";
    }

    private static String summaryKey(ConversationKey key) {
        return "memory:summary:" + key.value();
    }

    public record Window(String summary, List<Message.Stored> messages) {}
}
