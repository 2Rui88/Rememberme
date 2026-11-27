package io.github.rui88.memory.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.knuddels.jtokkit.api.EncodingType;
import io.github.rui88.memory.llm.TextTokens;
import io.github.rui88.memory.model.ConversationKey;
import io.github.rui88.memory.model.Message;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class ShortMemoryTest {
    private static final ConversationKey KEY = new ConversationKey("tenant", "person", "report", "session");

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
