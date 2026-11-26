package io.github.rui88.memory;

import static io.github.rui88.memory.ConversationJobs.Kind.LONG_MEMORY;
import static io.github.rui88.memory.ConversationJobs.Kind.SUMMARY;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class SessionEndMemory {
    private static final Logger log = LoggerFactory.getLogger(SessionEndMemory.class);
    private static final DefaultRedisScript<Long> MARK_SEEN = new DefaultRedisScript<>("""
            redis.call('SADD', KEYS[1], unpack(ARGV))
            return redis.call('EXPIRE', KEYS[1], 604800)
            """, Long.class);

    private final ConversationStore conversations;
    private final ConversationJobs jobs;
    private final AgentMemorySettings settings;
    private final MemoryJudge judge;
    private final LongMemory memory;
    private final ConversationSummarizer summarizer;
    private final StringRedisTemplate redis;
    private final Executor executor;
    private final Set<Work> queued = ConcurrentHashMap.newKeySet();

    SessionEndMemory(ConversationStore conversations, ConversationJobs jobs, AgentMemorySettings settings,
                     MemoryJudge judge, LongMemory memory, ConversationSummarizer summarizer,
                     StringRedisTemplate redis, @Qualifier("memoryWriteExecutor") Executor executor) {
        this.conversations = conversations;
        this.jobs = jobs;
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
            jobs.requestMemory(key, head);
        }
        return head;
    }

    @Scheduled(fixedDelayString = "${memory.jobs.poll-interval:1s}")
    void dispatch() {
        if (summarizer.configured()) {
            enqueue(SUMMARY, jobs.ready(SUMMARY));
        }
        enqueue(LONG_MEMORY, jobs.ready(LONG_MEMORY));
    }

    @Scheduled(fixedDelay = 60_000)
    void expireAbandonedTurns() {
        jobs.expireTurns();
    }

    private void enqueue(ConversationJobs.Kind kind, List<ConversationKey> keys) {
        for (ConversationKey key : keys) {
            Work work = new Work(kind, key);
            if (!queued.add(work)) {
                continue;
            }
            try {
                executor.execute(() -> process(work));
            } catch (RejectedExecutionException error) {
                queued.remove(work);
                return;
            }
        }
    }

    private void process(Work work) {
        String owner = UUID.randomUUID().toString();
        ConversationJobs.Snapshot snapshot = null;
        boolean completed = false;
        boolean retry = true;
        try {
            snapshot = jobs.claim(work.kind(), work.key(), owner);
            if (snapshot == null) {
                return;
            }
            if (work.kind() == SUMMARY) {
                summarizer.refresh(work.key(), snapshot.through());
                retry = false;
            } else if (consolidate(snapshot)) {
                jobs.completeMemory(snapshot, owner);
                completed = true;
            }
        } catch (RuntimeException error) {
            log.warn("Conversation {} processing failed ({})", work.kind(), error.getClass().getSimpleName());
        } finally {
            try {
                if (snapshot != null && !completed) {
                    jobs.release(work.kind(), work.key(), owner, retry);
                }
            } finally {
                queued.remove(work);
            }
        }
    }

    private boolean consolidate(ConversationJobs.Snapshot snapshot) {
        ConversationKey key = snapshot.key();
        AgentMemorySettings.Options options = settings.get(key);
        if (!options.enabled()) {
            return false;
        }
        List<Message.Stored> history = conversations.range(key, 0, snapshot.through(), Integer.MAX_VALUE);
        String redisKey = "memory:seen:" + key.value();
        Set<String> seen = previouslySeen(redisKey);
        List<Message.Stored> fresh = new ArrayList<>();
        List<String> hashes = new ArrayList<>();
        for (Message.Stored entry : history) {
            if (entry.sequence() <= snapshot.memoryThrough()) {
                continue;
            }
            String hash = digest(MemoryJudge.messageKey(entry.message()));
            if (seen.add(hash)) {
                fresh.add(entry);
                hashes.add(hash);
            }
        }
        if (fresh.isEmpty()) {
            return true;
        }
        LongMemory.WriteResult result = memory.save(key, options, judge.judge(history, fresh));
        log.info("Session memory processed through {}: {} written, {} failures",
                snapshot.through(), result.written(), result.failures());
        if (result.failures() != 0) {
            return false;
        }
        markSeen(redisKey, hashes);
        return true;
    }

    private Set<String> previouslySeen(String redisKey) {
        try {
            Set<String> values = redis.opsForSet().members(redisKey);
            return values == null ? new HashSet<>() : new HashSet<>(values);
        } catch (RuntimeException error) {
            log.warn("Could not read processed message hashes; using durable sequence");
            return new HashSet<>();
        }
    }

    private void markSeen(String redisKey, List<String> hashes) {
        try {
            for (int start = 0; start < hashes.size(); start += 500) {
                redis.execute(MARK_SEEN, List.of(redisKey),
                        hashes.subList(start, Math.min(start + 500, hashes.size())).toArray());
            }
        } catch (RuntimeException error) {
            log.warn("Could not mark all processed message hashes");
        }
    }

    private static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("MD5")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

    private record Work(ConversationJobs.Kind kind, ConversationKey key) {}
}
