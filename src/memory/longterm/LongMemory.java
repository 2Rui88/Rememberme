package memory.longterm;

import static memory.longterm.MemoryProvider.Scope;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.IntConsumer;

import memory.llm.TextTokens;
import memory.model.ConversationKey;
import memory.model.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class LongMemory {
    private static final Logger log = LoggerFactory.getLogger(LongMemory.class);
    private static final int TOTAL_BUDGET = 4000;
    private static final int PROFILE_LIMIT = 2400;
    private static final int MAX_PER_SCOPE = 20;
    private static final Comparator<MemoryProvider.Hit> RELEVANCE =
            Comparator.comparing(MemoryProvider.Hit::score, Comparator.nullsLast(Comparator.reverseOrder()));

    private final Map<MemoryProvider.Kind, MemoryProvider> providers = new EnumMap<>(MemoryProvider.Kind.class);
    private final TextTokens tokens;
    private final MemoryJudge judge;

    public LongMemory(List<MemoryProvider> providers, TextTokens tokens, MemoryJudge judge) {
        providers.forEach(provider -> this.providers.put(provider.kind(), provider));
        this.tokens = tokens;
        this.judge = judge;
    }

    public Context load(ConversationKey key, String query, AgentMemorySettings.Options options) {
        if (!options.enabled() || query.isBlank()) {
            return Context.EMPTY;
        }
        try {
            List<MemoryProvider.Hit> hits = provider(options.provider()).search(key, query, Set.of(Scope.USER, Scope.AGENT))
                    .stream()
                    .filter(hit -> hit.content() != null && !hit.content().isBlank())
                    .filter(hit -> hit.score() == null || hit.score() >= .3)
                    .filter(hit -> !options.filterSensitive() || !MemoryJudge.sensitive(hit.content()))
                    .sorted(RELEVANCE).toList();
            String profile = withinBudget(hits, Scope.USER, PROFILE_LIMIT);
            String agent = withinBudget(hits, Scope.AGENT, TOTAL_BUDGET - tokens.count(profile));
            return new Context(profile, agent);
        } catch (RuntimeException error) {
            log.warn("Long memory search failed for provider {} ({})",
                    options.provider(), error.getClass().getSimpleName());
            return Context.EMPTY;
        }
    }

    public WriteResult save(ConversationKey key, AgentMemorySettings.Options options,
                            List<MemoryProvider.Candidate> candidates, List<Message.Stored> history) {
        MemoryProvider provider = provider(options.provider());
        Map<Scope, List<MemoryProvider.Candidate>> byScope = groupByScope(candidates);
        if (provider instanceof MemosMemoryProvider memos) {
            return saveMemos(key, memos, planMemosWrites(byScope), history, () -> {}, index -> {});
        }
        int written = 0;
        int failures = 0;
        for (var entry : byScope.entrySet()) {
            List<MemoryProvider.Candidate> distinct = new ArrayList<>();
            entry.getValue().forEach(candidate -> addIfDistinct(distinct, candidate));
            for (MemoryProvider.Candidate candidate : distinct) {
                try {
                    List<MemoryProvider.Hit> existing = provider.search(key, candidate.content(), Set.of(entry.getKey()))
                            .stream().filter(hit -> hit.scope() == entry.getKey()).limit(10).toList();
                    String normalized = MemoryText.normalized(candidate.content());
                    if (existing.stream().anyMatch(hit -> normalized.equals(MemoryText.normalized(hit.content())))) {
                        continue;
                    }
                    MemoryJudge.Resolution resolution = judge.resolve(candidate, existing);
                    if (!resolution.store()) {
                        continue;
                    }
                    if (!provider.add(key, candidate)) {
                        failures++;
                        log.warn("Long memory add was unconfirmed for provider {}", provider.kind());
                        continue;
                    }
                    written++;
                    if (!resolution.oldIds().isEmpty()) {
                        provider.delete(key, entry.getKey(), resolution.oldIds());
                    }
                } catch (RuntimeException error) {
                    failures++;
                    log.warn("Long memory write or cleanup failed for provider {} ({})",
                            provider.kind(), error.getClass().getSimpleName());
                }
            }
        }
        return new WriteResult(written, failures);
    }

    public static List<MemosWrite> planMemosWrites(List<MemoryProvider.Candidate> candidates) {
        return planMemosWrites(groupByScope(candidates));
    }

    private static List<MemosWrite> planMemosWrites(Map<Scope, List<MemoryProvider.Candidate>> byScope) {
        List<MemosWrite> writes = new ArrayList<>();
        for (var group : byScope.entrySet()) {
            Scope scope = group.getKey();
            List<MemoryProvider.Candidate> additions = new ArrayList<>();
            for (MemoryProvider.Candidate candidate : group.getValue()) {
                if (candidate.category() == MemoryProvider.Category.CORRECTION) {
                    if (!additions.isEmpty()) {
                        writes.add(new MemosWrite(scope, List.copyOf(additions), false));
                        additions.clear();
                    }
                    writes.add(new MemosWrite(scope, List.of(candidate), false));
                } else {
                    addIfDistinct(additions, candidate);
                }
            }
            if (!additions.isEmpty()) {
                writes.add(new MemosWrite(scope, List.copyOf(additions), false));
            }
        }
        return List.copyOf(writes);
    }

    public WriteResult saveMemos(ConversationKey key, List<MemosWrite> writes, List<Message.Stored> history,
                                 Runnable renewClaim, IntConsumer confirmWrite) {
        return saveMemos(key, (MemosMemoryProvider) provider(MemoryProvider.Kind.MEMOS), writes, history,
                renewClaim, confirmWrite);
    }

    private static WriteResult saveMemos(ConversationKey key, MemosMemoryProvider memos,
                                         List<MemosWrite> writes, List<Message.Stored> history,
                                         Runnable renewClaim, IntConsumer confirmWrite) {
        int written = 0;
        Set<Scope> failedScopes = EnumSet.noneOf(Scope.class);
        for (int index = 0; index < writes.size(); index++) {
            MemosWrite write = writes.get(index);
            if (write.confirmed() || failedScopes.contains(write.scope())) {
                continue;
            }
            renewClaim.run();
            try {
                MemoryProvider.Candidate first = write.candidates().get(0);
                if (first.category() == MemoryProvider.Category.CORRECTION) {
                    memos.feedback(key, first, history);
                    written++;
                } else {
                    written += addMemosBatch(key, memos, write.scope(), write.candidates());
                }
            } catch (RuntimeException error) {
                failedScopes.add(write.scope());
                log.warn("MemOS Cube write or correction failed for scope {} ({})",
                        write.scope(), error.getClass().getSimpleName());
                continue;
            }
            confirmWrite.accept(index);
        }
        return new WriteResult(written, failedScopes.size());
    }

    private static int addMemosBatch(ConversationKey key, MemosMemoryProvider memos, Scope scope,
                                     List<MemoryProvider.Candidate> additions) {
        List<String> added = memos.addBatch(key, scope, additions);
        if (added.isEmpty()) {
            throw new IllegalStateException("MemOS did not confirm any memory additions");
        }
        return added.size();
    }

    private static Map<Scope, List<MemoryProvider.Candidate>> groupByScope(List<MemoryProvider.Candidate> candidates) {
        Map<Scope, List<MemoryProvider.Candidate>> byScope = new EnumMap<>(Scope.class);
        for (MemoryProvider.Candidate candidate : candidates) {
            byScope.computeIfAbsent(candidate.scope(), unused -> new ArrayList<>()).add(candidate);
        }
        return byScope;
    }

    private static void addIfDistinct(List<MemoryProvider.Candidate> texts, MemoryProvider.Candidate candidate) {
        if (texts.stream().noneMatch(existing -> MemoryText.similar(existing.content(), candidate.content()))) {
            texts.add(candidate);
        }
    }

    private MemoryProvider provider(MemoryProvider.Kind kind) {
        return Objects.requireNonNull(providers.get(kind == null ? MemoryProvider.Kind.MYSQL : kind),
                "Long memory provider unavailable");
    }

    private String withinBudget(List<MemoryProvider.Hit> hits, Scope scope, int budget) {
        StringBuilder assembled = new StringBuilder();
        for (MemoryProvider.Hit hit : hits.stream().filter(hit -> hit.scope() == scope).limit(MAX_PER_SCOPE).toList()) {
            String content = MemoryText.limit(hit.content().replaceAll("\\s+", " ").strip(), 1000);
            String line = "- " + content + "\n";
            if (tokens.count(assembled.toString() + line) > budget) {
                break;
            }
            assembled.append(line);
        }
        return assembled.toString();
    }

    public record Context(String userProfile, String agentMemory) {
        public static final Context EMPTY = new Context("", "");
    }

    public record WriteResult(int written, int failures) {}

    public record MemosWrite(Scope scope, List<MemoryProvider.Candidate> candidates, boolean confirmed) {}
}
