package io.github.rui88.memory;

import static io.github.rui88.memory.MemoryProvider.Scope;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

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
                            List<MemoryProvider.Candidate> candidates) {
        MemoryProvider provider = provider(options.provider());
        Map<Scope, List<MemoryProvider.Candidate>> byScope = new EnumMap<>(Scope.class);
        for (MemoryProvider.Candidate candidate : candidates) {
            List<MemoryProvider.Candidate> scope = byScope.computeIfAbsent(candidate.scope(), unused -> new ArrayList<>());
            if (scope.stream().noneMatch(existing -> MemoryText.similar(existing.content(), candidate.content()))) {
                scope.add(candidate);
            }
        }
        int written = 0;
        int failures = 0;
        for (var entry : byScope.entrySet()) {
            for (MemoryProvider.Candidate candidate : entry.getValue()) {
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
}
