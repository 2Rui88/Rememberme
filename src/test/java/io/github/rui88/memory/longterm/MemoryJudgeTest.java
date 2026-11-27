package io.github.rui88.memory.longterm;

import static io.github.rui88.memory.longterm.MemoryProvider.Category.CORRECTION;
import static io.github.rui88.memory.longterm.MemoryProvider.Category.PREFERENCE;
import static io.github.rui88.memory.longterm.MemoryProvider.Scope.USER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.rui88.memory.llm.LanguageModel;
import io.github.rui88.memory.model.Message;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class MemoryJudgeTest {
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
    void ruleFallbackRecognizesAnExplicitChangeBeforeTheGeneralPreferenceRule() {
        MemoryJudge judge = new MemoryJudge(mock(LanguageModel.class), new ObjectMapper());
        var change = new Message.Stored(2, new Message(Message.Role.USER, "以后改用英文回复"));

        assertThat(judge.judge(List.of(change), List.of(change)))
                .containsExactly(new MemoryProvider.Candidate("以后改用英文回复", USER, CORRECTION, 8, 2));
    }
}
