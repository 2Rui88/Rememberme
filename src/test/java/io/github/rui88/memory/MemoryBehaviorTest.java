package io.github.rui88.memory;

import static io.github.rui88.memory.MemoryProvider.Category.CORRECTION;
import static io.github.rui88.memory.MemoryProvider.Category.PREFERENCE;
import static io.github.rui88.memory.MemoryProvider.Scope.AGENT;
import static io.github.rui88.memory.MemoryProvider.Scope.USER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.knuddels.jtokkit.api.EncodingType;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.ValueOperations;

class MemoryBehaviorTest {
    private static final ConversationKey KEY = new ConversationKey("tenant", "person", "report", "session");
    private static final AgentMemorySettings.Options OPTIONS =
            new AgentMemorySettings.Options(true, MemoryProvider.Kind.MYSQL, 20, true);

    @Test
    void modelReceivesOldContextAndOnlyMarksMatchingNewMessages() throws Exception {
        LanguageModel model = mock(LanguageModel.class);
        when(model.configured()).thenReturn(true);
        when(model.complete(anyString(), anyString())).thenReturn("""
                {"memories":[{"content":"Write reports with conclusions first","scope":"USER",
                  "category":"PREFERENCE","importance":7,"sourceSequence":2}]}
                """);
        ObjectMapper json = new ObjectMapper();
        MemoryJudge judge = new MemoryJudge(model, json);
        Message.Stored old = new Message.Stored(1, new Message(Message.Role.USER, "Conclusions first."));
        Message.Stored fresh = new Message.Stored(2, new Message(Message.Role.USER, "Keep that format."));

        assertThat(judge.judge(List.of(old, fresh), List.of(fresh)))
                .containsExactly(new MemoryProvider.Candidate("Write reports with conclusions first", USER, PREFERENCE, 7, 2));
        ArgumentCaptor<String> input = ArgumentCaptor.forClass(String.class);
        verify(model).complete(anyString(), input.capture());
        var transcript = json.readTree(input.getValue());
        assertThat(transcript.path(0).path("new").asBoolean()).isFalse();
        assertThat(transcript.path(0).path("content").asText()).isEqualTo("Conclusions first.");
        assertThat(transcript.path(1).path("new").asBoolean()).isTrue();
        assertThat(transcript.path(1).path("sequence").asLong()).isEqualTo(2);
    }

    @Test
    void replacementOnlyDeletesAfterConfirmedAdd() {
        MemoryProvider provider = mock(MemoryProvider.class);
        MemoryJudge judge = mock(MemoryJudge.class);
        when(provider.kind()).thenReturn(MemoryProvider.Kind.MYSQL);
        var candidate = new MemoryProvider.Candidate("Conclusion first", USER, PREFERENCE, 7);
        var existing = List.of(new MemoryProvider.Hit("old", USER, "Evidence first", .9));
        when(provider.search(KEY, candidate.content(), Set.of(USER))).thenReturn(existing);
        when(judge.resolve(candidate, existing)).thenReturn(new MemoryJudge.Resolution(true, List.of("old")));
        when(provider.add(KEY, candidate)).thenReturn(false, true);
        LongMemory memory = new LongMemory(List.of(provider), new TextTokens(EncodingType.CL100K_BASE), judge);

        assertThat(memory.save(KEY, OPTIONS, List.of(candidate), List.of())).isEqualTo(new LongMemory.WriteResult(0, 1));
        verify(provider, never()).delete(any(), any(), any());
        assertThat(memory.save(KEY, OPTIONS, List.of(candidate), List.of())).isEqualTo(new LongMemory.WriteResult(1, 0));
        var ordered = inOrder(provider);
        ordered.verify(provider, org.mockito.Mockito.times(2)).add(KEY, candidate);
        ordered.verify(provider).delete(KEY, USER, List.of("old"));
    }

    @Test
    void memosKeepsAnExplicitCorrectionAfterASimilarAdditionInTheSameCube() {
        MemosMemoryProvider provider = mock(MemosMemoryProvider.class);
        when(provider.kind()).thenReturn(MemoryProvider.Kind.MEMOS);
        MemoryJudge judge = mock(MemoryJudge.class);
        var initial = new MemoryProvider.Candidate("以后都用中文回复", USER, PREFERENCE, 7, 1);
        var correction = new MemoryProvider.Candidate("以后都用英文回复", USER, CORRECTION, 8, 2);
        assertThat(MemoryText.similar(initial.content(), correction.content())).isTrue();
        var agentRule = new MemoryProvider.Candidate("Check totals first", AGENT, PREFERENCE, 7, 3);
        var history = List.of(new Message.Stored(1, new Message(Message.Role.USER, initial.content())),
                new Message.Stored(2, new Message(Message.Role.USER, "以后改用英文回复")),
                new Message.Stored(3, new Message(Message.Role.USER, agentRule.content())));
        when(provider.addBatch(KEY, USER, List.of(initial))).thenReturn(List.of());
        when(provider.addBatch(KEY, AGENT, List.of(agentRule))).thenReturn(List.of("agent-id"));
        var options = new AgentMemorySettings.Options(true, MemoryProvider.Kind.MEMOS, 20, true);
        LongMemory memory = new LongMemory(List.of(provider), new TextTokens(EncodingType.CL100K_BASE), judge);

        assertThat(memory.save(KEY, options, List.of(initial, correction, agentRule), history))
                .isEqualTo(new LongMemory.WriteResult(2, 0));

        var ordered = inOrder(provider);
        ordered.verify(provider).addBatch(KEY, USER, List.of(initial));
        ordered.verify(provider).feedback(KEY, correction, history);
        ordered.verify(provider).addBatch(KEY, AGENT, List.of(agentRule));
        verifyNoInteractions(judge);
        verify(provider, never()).delete(any(), any(), any());
    }

    @Test
    void ruleFallbackRecognizesAnExplicitChangeBeforeTheGeneralPreferenceRule() {
        MemoryJudge judge = new MemoryJudge(mock(LanguageModel.class), new ObjectMapper());
        var change = new Message.Stored(2, new Message(Message.Role.USER, "以后改用英文回复"));

        assertThat(judge.judge(List.of(change), List.of(change)))
                .containsExactly(new MemoryProvider.Candidate("以后改用英文回复", USER, CORRECTION, 8, 2));
    }

    @Test
    void filteringAndCountingApplyAcrossBothLongMemoryScopes() {
        TextTokens tokens = new TextTokens(EncodingType.CL100K_BASE);
        MemoryProvider provider = mock(MemoryProvider.class);
        when(provider.kind()).thenReturn(MemoryProvider.Kind.MYSQL);
        List<MemoryProvider.Hit> hits = new ArrayList<>();
        hits.add(new MemoryProvider.Hit("low", USER, "irrelevant preference", .1));
        hits.add(new MemoryProvider.Hit("secret", USER, "password: abcdef123", 1.0));
        for (int i = 0; i < 20; i++) {
            hits.add(new MemoryProvider.Hit("u" + i, USER, "用户习惯".repeat(150) + i, .8));
            hits.add(new MemoryProvider.Hit("a" + i, AGENT, "流程步骤".repeat(150) + i, .8));
        }
        when(provider.search(KEY, "report", Set.of(USER, AGENT))).thenReturn(hits);
        LongMemory memory = new LongMemory(List.of(provider), tokens, mock(MemoryJudge.class));

        LongMemory.Context result = memory.load(KEY, "report", OPTIONS);
        assertThat(result.userProfile()).doesNotContain("password", "irrelevant").isNotBlank();
        assertThat(result.agentMemory()).isNotBlank();
        assertThat(tokens.count(result.userProfile())).isLessThanOrEqualTo(2400);
        assertThat(tokens.count(result.userProfile()) + tokens.count(result.agentMemory())).isLessThanOrEqualTo(4000);
    }

    @Test
    void shortHistoryKeepsCompleteToolResultsAndDropsUnresolvedCalls() {
        ConversationStore store = mock(ConversationStore.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        org.mockito.Mockito.doThrow(new IllegalStateException("cache unavailable")).when(redis).opsForList();
        Message.ToolCall call = new Message.ToolCall("call-1", "lookup", "{}");
        List<Message.Stored> rows = List.of(
                new Message.Stored(1, new Message(Message.Role.ASSISTANT, "Earlier response")),
                new Message.Stored(2, new Message(Message.Role.USER, "Please look this up")),
                new Message.Stored(3, new Message(Message.Role.ASSISTANT, "Looking up", null, List.of(call))),
                new Message.Stored(4, new Message(Message.Role.USER, "Try again")),
                new Message.Stored(5, new Message(Message.Role.ASSISTANT, "", null, List.of(call))),
                new Message.Stored(6, new Message(Message.Role.TOOL, "Found it", "call-1", List.of())));
        when(store.recent(KEY, 200)).thenReturn(rows);
        ShortMemory memory = new ShortMemory(store, redis, new ObjectMapper(),
                new TextTokens(EncodingType.CL100K_BASE), 64000);

        List<Message.Stored> loaded = memory.load(KEY).messages();
        assertThat(loaded).extracting(Message.Stored::sequence).containsExactly(2L, 3L, 4L, 5L, 6L);
        assertThat(loaded.get(1).message().toolCalls()).isEmpty();
        assertThat(loaded.get(3).message().toolCalls()).containsExactly(call);
    }

    @Test
    void shortHistoryCacheHitKeepsAllEightyShortMessagesWithoutReadingMysql() throws Exception {
        ConversationStore store = mock(ConversationStore.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ListOperations<String, String> history = mock();
        when(redis.opsForList()).thenReturn(history);
        ObjectMapper json = new ObjectMapper();
        List<String> cached = new ArrayList<>();
        for (int seq = 80; seq > 0; seq--) {
            Message.Role role = seq % 2 == 1 ? Message.Role.USER : Message.Role.ASSISTANT;
            cached.add(json.writeValueAsString(new Message.Stored(seq, new Message(role, "short"))));
        }
        when(history.range(anyString(), org.mockito.ArgumentMatchers.eq(0L), org.mockito.ArgumentMatchers.eq(-1L)))
                .thenReturn(cached);
        ShortMemory memory = new ShortMemory(store, redis, json, new TextTokens(EncodingType.CL100K_BASE), 64000);

        ShortMemory.Window loaded = memory.load(KEY);

        assertThat(loaded.messages()).hasSize(80);
        assertThat(loaded.messages().get(0).sequence()).isEqualTo(1);
        assertThat(loaded.summary()).isEmpty();
        verifyNoInteractions(store);
        verify(redis, never()).opsForValue();
    }

    @Test
    void overflowUsesTheSameRecentWindowWithOrWithoutAnExistingSummary() throws Exception {
        ConversationStore store = mock(ConversationStore.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> summaries = mock();
        when(redis.opsForValue()).thenReturn(summaries);
        var older = new Message.Stored(1, new Message(Message.Role.USER, " detail".repeat(64_000)));
        var recent = new Message.Stored(2, new Message(Message.Role.USER, "Latest question"));
        when(store.recent(KEY, 200)).thenReturn(List.of(older, recent));
        when(store.summary(KEY)).thenReturn(ConversationStore.Summary.EMPTY);
        ObjectMapper json = new ObjectMapper();
        when(summaries.get(anyString()))
                .thenReturn(json.writeValueAsString(new ConversationStore.Summary(1, "Earlier context")), (String) null);
        ShortMemory memory = new ShortMemory(store, redis, json, new TextTokens(EncodingType.CL100K_BASE), 64000);

        assertThat(memory.load(KEY)).isEqualTo(new ShortMemory.Window("Earlier context", List.of(recent)));
        assertThat(memory.load(KEY)).isEqualTo(new ShortMemory.Window("", List.of(recent)));
    }
}
