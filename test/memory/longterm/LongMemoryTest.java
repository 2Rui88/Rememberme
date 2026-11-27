package memory.longterm;

import static memory.longterm.MemoryProvider.Category.CORRECTION;
import static memory.longterm.MemoryProvider.Category.PREFERENCE;
import static memory.longterm.MemoryProvider.Scope.AGENT;
import static memory.longterm.MemoryProvider.Scope.USER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.knuddels.jtokkit.api.EncodingType;
import memory.llm.TextTokens;
import memory.model.ConversationKey;
import memory.model.Message;
import org.junit.jupiter.api.Test;

class LongMemoryTest {
    private static final ConversationKey KEY = new ConversationKey("tenant", "person", "report", "session");
    private static final AgentMemorySettings.Options OPTIONS =
            new AgentMemorySettings.Options(true, MemoryProvider.Kind.MYSQL, 20, true);

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
        when(provider.addBatch(KEY, USER, List.of(initial))).thenReturn(List.of("user-id"));
        when(provider.addBatch(KEY, AGENT, List.of(agentRule))).thenReturn(List.of("agent-id"));
        var options = new AgentMemorySettings.Options(true, MemoryProvider.Kind.MEMOS, 20, true);
        LongMemory memory = new LongMemory(List.of(provider), new TextTokens(EncodingType.CL100K_BASE), judge);

        assertThat(memory.save(KEY, options, List.of(initial, correction, agentRule), history))
                .isEqualTo(new LongMemory.WriteResult(3, 0));

        var ordered = inOrder(provider);
        ordered.verify(provider).addBatch(KEY, USER, List.of(initial));
        ordered.verify(provider).feedback(KEY, correction, history);
        ordered.verify(provider).addBatch(KEY, AGENT, List.of(agentRule));
        verifyNoInteractions(judge);
        verify(provider, never()).delete(any(), any(), any());
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
}
