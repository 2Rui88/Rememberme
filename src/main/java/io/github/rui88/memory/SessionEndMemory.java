package io.github.rui88.memory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

@Service
public class SessionEndMemory {
    private static final Logger log = LoggerFactory.getLogger(SessionEndMemory.class);
    private static final Duration LOCK_TTL = Duration.ofMinutes(10);
    private static final DefaultRedisScript<Long> RELEASE = new DefaultRedisScript<>("""
            if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('DEL', KEYS[1]) end
            return 0
            """, Long.class);
    private static final DefaultRedisScript<Long> MARK_SEEN = new DefaultRedisScript<>("""
            redis.call('SADD', KEYS[1], unpack(ARGV))
            return redis.call('EXPIRE', KEYS[1], 604800)
            """, Long.class);

    private final ConversationStore conversations;
    private final AgentMemorySettings settings;
    private final MemoryJudge judge;
    private final LongMemory memory;
    private final ConversationSummarizer summarizer;
    private final StringRedisTemplate redis;
    private final Executor executor;

    public SessionEndMemory(ConversationStore conversations, AgentMemorySettings settings, MemoryJudge judge,
                            LongMemory memory, ConversationSummarizer summarizer, StringRedisTemplate redis,
                            @Qualifier("memoryWriteExecutor") Executor executor) {
        this.conversations = conversations;
        this.settings = settings;
        this.judge = judge;
        this.memory = memory;
        this.summarizer = summarizer;
        this.redis = redis;
        this.executor = executor;
    }

    public long submit(ConversationKey key) {
        long head = conversations.head(key);
        if (head > 0) {
            executor.execute(() -> process(key, head));
        }
        return head;
    }

    void process(ConversationKey key, long head) {
        String lock = "memory:end-lock:" + key.value();
        String owner = UUID.randomUUID().toString();
        boolean acquired = false;
        try {
            try {
                acquired = Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(lock, owner, LOCK_TTL));
                if (!acquired) {
                    return;
                }
            } catch (RuntimeException error) {
                log.warn("Conversation end lock unavailable; processing is best effort");
            }
            AgentMemorySettings.Options options = settings.get(key);
            if (options.enabled()) {
                List<Message.Stored> history = conversations.range(key, 0, head, Integer.MAX_VALUE);
                List<Message.Stored> fresh = newMessages(key, history);
                if (!fresh.isEmpty()) {
                    LongMemory.WriteResult result = memory.save(key, options, judge.judge(history, fresh));
                    log.info("Session memory processed through {}: {} written, {} failures",
                            head, result.written(), result.failures());
                }
            }
        } catch (RuntimeException error) {
            log.warn("Session memory processing failed through {} ({})", head, error.getClass().getSimpleName());
        } finally {
            if (acquired) {
                try {
                    redis.execute(RELEASE, List.of(lock), owner);
                } catch (RuntimeException error) {
                    log.warn("Session end lock will expire naturally");
                }
            }
        }
        try {
            summarizer.refresh(key, head);
        } catch (RuntimeException error) {
            log.warn("Conversation summary failed through {} ({})", head, error.getClass().getSimpleName());
        }
    }

    private List<Message.Stored> newMessages(ConversationKey key, List<Message.Stored> history) {
        String redisKey = "memory:seen:" + key.value();
        Set<String> previouslySeen;
        try {
            Set<String> values = redis.opsForSet().members(redisKey);
            previouslySeen = values == null ? new HashSet<>() : new HashSet<>(values);
        } catch (RuntimeException error) {
            log.warn("Could not read processed message hashes; using full transcript");
            return history;
        }
        List<Message.Stored> fresh = new ArrayList<>();
        List<String> hashes = new ArrayList<>();
        for (Message.Stored entry : history) {
            String hash = digest(MemoryJudge.messageKey(entry.message()));
            if (previouslySeen.add(hash)) {
                fresh.add(entry);
                hashes.add(hash);
            }
        }
        try {
            for (int start = 0; start < hashes.size(); start += 500) {
                redis.execute(MARK_SEEN, List.of(redisKey),
                        hashes.subList(start, Math.min(start + 500, hashes.size())).toArray());
            }
        } catch (RuntimeException error) {
            log.warn("Could not mark all processed message hashes");
        }
        return fresh;
    }

    private static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("MD5")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }
}
