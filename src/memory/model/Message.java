package memory.model;

import java.util.List;
import java.util.Locale;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record Message(
        @NotNull Role role,
        @NotNull @Size(max = 262144) String content,
        @Size(max = 128) String toolCallId,
        @Size(max = 32) List<@Valid ToolCall> toolCalls) {

    public Message {
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        if (role == Role.TOOL && (toolCallId == null || toolCallId.isBlank())) {
            throw new IllegalArgumentException("Tool messages require a toolCallId");
        }
        if ((role != Role.TOOL && toolCallId != null) || (role != Role.ASSISTANT && !toolCalls.isEmpty())) {
            throw new IllegalArgumentException("Tool fields do not match the message role");
        }
    }

    public Message(Role role, String content) {
        this(role, content, null, List.of());
    }

    public enum Role {
        SYSTEM, USER, ASSISTANT, TOOL;

        @JsonCreator
        public static Role from(String value) {
            return valueOf(value.toUpperCase(Locale.ROOT));
        }

        @JsonValue
        public String wireValue() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    public record ToolCall(@NotBlank @Size(max = 128) String id,
                           @NotBlank @Size(max = 128) String name,
                           @NotNull @Size(max = 65536) String arguments) {}

    public record Stored(long sequence, Message message) {}
}
