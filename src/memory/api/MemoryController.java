package memory.api;

import java.util.List;
import java.util.Map;
import java.util.concurrent.RejectedExecutionException;

import memory.conversation.AgentExecutor;
import memory.conversation.SessionEndMemory;
import memory.conversation.ShortMemory;
import memory.llm.LanguageModel;
import memory.longterm.AgentMemorySettings;
import memory.model.ConversationKey;
import memory.model.Message;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/tenants/{tenant}/users/{user}/agents/{agent}")
public class MemoryController {
    private final ShortMemory shortMemory;
    private final AgentExecutor agent;
    private final SessionEndMemory endMemory;
    private final AgentMemorySettings settings;
    private final LanguageModel model;

    public MemoryController(ShortMemory shortMemory, AgentExecutor agent, SessionEndMemory endMemory,
                            AgentMemorySettings settings, LanguageModel model) {
        this.shortMemory = shortMemory;
        this.agent = agent;
        this.endMemory = endMemory;
        this.settings = settings;
        this.model = model;
    }

    @GetMapping("/settings")
    public AgentMemorySettings.Options settings(@PathVariable String tenant, @PathVariable String user,
                                                 @PathVariable String agent) {
        return settings.get(new ConversationKey(tenant, user, agent, "settings"));
    }

    @PutMapping("/settings")
    public AgentMemorySettings.Options updateSettings(@PathVariable String tenant, @PathVariable String user,
                                                       @PathVariable String agent,
                                                       @RequestBody AgentMemorySettings.Options request) {
        return settings.update(new ConversationKey(tenant, user, agent, "settings"), request);
    }

    @PostMapping("/sessions/{session}/messages")
    public List<Message.Stored> append(@PathVariable String tenant, @PathVariable String user,
                                       @PathVariable String agent, @PathVariable String session,
                                       @RequestBody @Valid Append request) {
        return shortMemory.append(new ConversationKey(tenant, user, agent, session), request.messages());
    }

    @PostMapping("/sessions/{session}/context")
    public AgentExecutor.Context context(@PathVariable String tenant, @PathVariable String user,
                                         @PathVariable String agent, @PathVariable String session,
                                         @RequestBody @Valid Query request) {
        return this.agent.load(new ConversationKey(tenant, user, agent, session), request.message());
    }

    @PostMapping("/sessions/{session}/turn")
    public AgentExecutor.Reply turn(@PathVariable String tenant, @PathVariable String user,
                                    @PathVariable String agent, @PathVariable String session,
                                    @RequestBody @Valid Query request) {
        if (!model.configured()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "No generation model configured");
        }
        return this.agent.turn(new ConversationKey(tenant, user, agent, session), request.message());
    }

    @PostMapping("/sessions/{session}/end")
    public ResponseEntity<Map<String, Long>> end(@PathVariable String tenant, @PathVariable String user,
                                                 @PathVariable String agent, @PathVariable String session) {
        long through = endMemory.submit(new ConversationKey(tenant, user, agent, session));
        return ResponseEntity.accepted().body(Map.of("throughSequence", through));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException error) {
        return ResponseEntity.badRequest().body(Map.of("error", error.getMessage()));
    }

    @ExceptionHandler(RejectedExecutionException.class)
    public ResponseEntity<Void> busy() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
    }

    @ExceptionHandler(RestClientException.class)
    public ResponseEntity<Void> modelFailure() {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).build();
    }

    public record Append(@NotNull @Size(min = 1, max = 100) List<@NotNull @Valid Message> messages) {}

    public record Query(@NotBlank @Size(max = 32768) String message) {}
}
