package com.summit.harnessexample;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.summit.core.conversation.ConversationEntity;
import com.summit.core.conversation.message.AiMessageEntity;
import com.summit.core.conversation.message.Message;
import com.summit.core.conversation.message.ToolMessageEntity;
import com.summit.core.conversation.message.UserMessageEntity;
import com.summit.core.runtime.LifeStyleCommandRegistry;
import com.summit.core.runtime.Workspace;
import com.summit.harnessexample.session_policy.RedisConversationStore;
import com.summit.harnessexample.session_policy.SessionSummary;
import com.summit.runtime.sandbox.DockerWorkspace;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Entry point for the simple coding agent.
 *
 * <p>The chat endpoint accepts a user instruction, then submits the execution
 * to a background thread so the HTTP request returns immediately. All runtime
 * events (model messages, tool calls, completion / error) are streamed to the
 * front-end in real-time through the SSE channel ({@code GET /agent/events}).</p>
 */
@Slf4j
@RestController
@RequestMapping("/agent")
@RequiredArgsConstructor
public class Controller {

    private final Demo demo;
    private final SseEventPublisher sseEventPublisher;
    private final RedisConversationStore conversationStore;
    /**
     * The active workspace bean: local (host) or Docker sandbox, per lingxi.agent.workspace.
     */
    private final Workspace workspace;
    private final ObjectMapper objectMapper;
    /**
     * Per-session lifecycle command registry. Every execution registers a fresh,
     * dedicated command store under its sessionId (see DefaultRuntimeFactory), so
     * pause/resume/stop issued here target exactly that session/execution and can
     * never leak into another execution's queue.
     */
    private final LifeStyleCommandRegistry lifeStyleCommandRegistry;
    /**
     * In-flight agent tasks (sessionId -> future), so a task can be stopped/cancelled
     * later and its running state queried by the control endpoints.
     */
    private final Map<String, CompletableFuture<Void>> runningTasks = new ConcurrentHashMap<>();


    /**
     * Opens a Server-Sent Events stream; runtime events are pushed onto it.
     */
    @GetMapping(value = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events() {
        return sseEventPublisher.connect();
    }

    @PostMapping("/chat")
    public ResponseEntity<Map<String, Object>> chat(@RequestBody Map<String, String> body) {

        String input = body == null ? null : body.get("input");
        boolean streaming = body != null && Boolean.parseBoolean(body.getOrDefault("streaming", "false"));

        if (input == null || input.isBlank()) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("code", 400);
            error.put("message", "input must not be blank");
            return ResponseEntity.badRequest().body(error);
        }

        // Resolve the conversation: reuse an existing sessionId or create a new one.
        String sessionId = body.get("sessionId");
        boolean newSession = sessionId == null || sessionId.isBlank();
        if (newSession) {
            sessionId = UUID.randomUUID().toString();
        }
        String sessionName = body.get("sessionName");
        if (sessionName == null || sessionName.isBlank()) {
            sessionName = defaultSessionName(input);
        }

        // Run the coding agent asynchronously; events are pushed via SSE.
        String finalSessionId = sessionId;
        String finalSessionName = sessionName;
        CompletableFuture<Void> task = CompletableFuture.runAsync(() -> demo.chat(input, streaming, finalSessionId, finalSessionName, workspace));
        runningTasks.put(finalSessionId, task);
        task.whenComplete((result, error) -> runningTasks.remove(finalSessionId, task));

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("input", input);
        data.put("streaming", streaming);
        data.put("sessionId", sessionId);
        data.put("sessionName", sessionName);
        data.put("newSession", newSession);
        data.put("sseClients", sseEventPublisher.connectedCount());
        data.put("runningSessions", runningTasks.size());

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("code", 200);
        response.put("message", "agent task submitted, events will be streamed via sse://{host}/agent/events");
        response.put("data", data);
        return ResponseEntity.ok(response);
    }

    /**
     * Pauses an agent-loop at its next checkpoint. Without {@code sessionId}
     * every running session is paused; with a {@code sessionId} only that
     * session's execution is paused. A paused loop blocks until {@link #resume}
     * or {@link #stop} is called for the same session.
     */
    @PostMapping("/pause")
    public ResponseEntity<Map<String, Object>> pause(@RequestParam(value = "sessionId", required = false) String sessionId) {
        return control("pause", sessionId);
    }

    /**
     * Resumes a paused agent-loop. Without {@code sessionId} every paused
     * session is resumed; with a {@code sessionId} only that session.
     */
    @PostMapping("/resume")
    public ResponseEntity<Map<String, Object>> resume(@RequestParam(value = "sessionId", required = false) String sessionId) {
        return control("resume", sessionId);
    }

    /**
     * Stops agent execution. Without {@code sessionId} every running loop is
     * stopped; with a {@code sessionId} only that session's execution (its
     * background task is cancelled first for immediate effect while paused).
     *
     * <p>Stopping is cooperative: a loop observes the command at its next
     * checkpoint, i.e. after the current model/tool step returns. The execution
     * is then reported as CANCELLED via SSE ({@code EXECUTION_CANCELLED}).</p>
     */
    @PostMapping("/stop")
    public ResponseEntity<Map<String, Object>> stop(@RequestParam(value = "sessionId", required = false) String sessionId) {
        return control("stop", sessionId);
    }

    private ResponseEntity<Map<String, Object>> control(String action, String sessionId) {
        Map<String, Object> data = new LinkedHashMap<>();
        if (sessionId != null && !runningTasks.containsKey(sessionId)) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("code", 404);
            error.put("message", "no running execution for sessionId: " + sessionId);
            error.put("data", Map.of("sessionId", sessionId, "runningSessions", runningTasks.size()));
            return ResponseEntity.status(404).body(error);
        }

        if (sessionId == null && runningTasks.isEmpty()) {
            // no live agent-loop: do NOT enqueue the command, it would leak into
            // the next execution started later
            data.put("action", action);
            data.put("runningSessions", 0);
            data.put("applied", false);
            data.put("message", "no running agent execution, command ignored");
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("code", 200);
            response.put("message", "ok");
            response.put("data", data);
            return ResponseEntity.ok(response);
        }

        if ("stop".equals(action) && sessionId != null) {
            // interrupt the loop thread; the checkpointer turns the interrupt
            // into a CANCELLED state while paused, otherwise it is best-effort
            runningTasks.get(sessionId).cancel(true);
        }

        if (sessionId != null) {
            switch (action) {
                case "pause" -> lifeStyleCommandRegistry.pause(sessionId);
                case "resume" -> lifeStyleCommandRegistry.resume(sessionId);
                case "stop" -> lifeStyleCommandRegistry.stop(sessionId);
                default -> {
                    Map<String, Object> error = new LinkedHashMap<>();
                    error.put("code", 400);
                    error.put("message", "unsupported control action: " + action);
                    return ResponseEntity.badRequest().body(error);
                }
            }
        } else {
            switch (action) {
                case "pause" -> lifeStyleCommandRegistry.pauseAll();
                case "resume" -> lifeStyleCommandRegistry.resumeAll();
                case "stop" -> lifeStyleCommandRegistry.stopAll();
                default -> {
                    Map<String, Object> error = new LinkedHashMap<>();
                    error.put("code", 400);
                    error.put("message", "unsupported control action: " + action);
                    return ResponseEntity.badRequest().body(error);
                }
            }
        }

        data.put("action", action);
        data.put("runningSessions", runningTasks.size());
        data.put("applied", true);
        data.put("sessionId", sessionId);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("code", 200);
        response.put("message", action + " command enqueued, will take effect at the next loop checkpoint");
        response.put("data", data);
        return ResponseEntity.ok(response);
    }

    /**
     * Returns all conversations persisted in the conversation store (lightweight summaries).
     */
    @GetMapping("/sessions")
    public ResponseEntity<Map<String, Object>> sessions() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (SessionSummary summary : conversationStore.sessionSummaries()) {
            Map<String, Object> session = new LinkedHashMap<>();
            session.put("sessionId", summary.sessionId());
            session.put("sessionName", summary.sessionName() == null || summary.sessionName().isBlank()
                    ? defaultSessionName(String.valueOf(summary.sessionId()))
                    : summary.sessionName());
            list.add(session);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("sessions", list);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("code", 200);
        response.put("message", "ok");
        response.put("data", data);
        return ResponseEntity.ok(response);
    }

    /**
     * Returns the message history of one session, mapped to simple display DTOs
     * (role: USER / AI / TOOL) so the front-end can render its chat bubbles.
     */
    @GetMapping("/sessions/{sessionId}/messages")
    public ResponseEntity<Map<String, Object>> sessionMessages(@PathVariable("sessionId") String sessionId) {
        Optional<ConversationEntity> entity = conversationStore.get(sessionId);
        if (entity.isEmpty()) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("code", 404);
            error.put("message", "session not found: " + sessionId);
            return ResponseEntity.status(404).body(error);
        }

        List<Map<String, Object>> messages = new ArrayList<>();
        for (Message message : entity.get().messages()) {
            Map<String, Object> item = new LinkedHashMap<>();
            if (message instanceof UserMessageEntity user) {
                item.put("role", "USER");
                item.put("text", user.text());
            } else if (message instanceof AiMessageEntity ai) {
                item.put("role", "AI");
                item.put("text", ai.text());
                item.put("thinking", ai.getThinking());
            } else if (message instanceof ToolMessageEntity tool) {
                item.put("role", "TOOL");
                item.put("toolName", tool.getName());
                item.put("text", tool.text());
            } else {
                continue;
            }
            messages.add(item);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("sessionId", sessionId);
        data.put("sessionName", entity.get().sessionName());
        data.put("messages", messages);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("code", 200);
        response.put("message", "ok");
        response.put("data", data);
        return ResponseEntity.ok(response);
    }

    /**
     * Simple liveness probe for the example app.
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("status", "UP");
        data.put("sessions", conversationStore.sessionSummaries().size());
        data.put("sseClients", sseEventPublisher.connectedCount());

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("code", 200);
        response.put("message", "ok");
        response.put("data", data);
        return ResponseEntity.ok(response);
    }

    /**
     * Deletes a conversation from the conversation store.
     */
    @PostMapping("/sessions/delete")
    public ResponseEntity<Map<String, Object>> deleteSession(@RequestBody Map<String, String> body) {

        return null;
    }

    /**
     * Renames an existing conversation (in-memory map only for now).
     */
    @PostMapping("/sessions/rename")
    public ResponseEntity<Map<String, Object>> renameSession(@RequestBody Map<String, String> body) {
        String sessionId = body == null ? null : body.get("sessionId");
        String sessionName = body == null ? null : body.get("sessionName");

        if (sessionId == null || sessionId.isBlank() || sessionName == null || sessionName.isBlank()) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("code", 400);
            error.put("message", "sessionId and sessionName must not be blank");
            return ResponseEntity.badRequest().body(error);
        }
        Optional<ConversationEntity> existing = conversationStore.get(sessionId);
        if (existing.isEmpty()) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("code", 404);
            error.put("message", "session not found: " + sessionId);
            return ResponseEntity.status(404).body(error);
        }
        conversationStore.save(sessionId, existing.get().withSessionName(sessionName));

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("sessionId", sessionId);
        data.put("sessionName", sessionName);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("code", 200);
        response.put("message", "ok");
        response.put("data", data);
        return ResponseEntity.ok(response);
    }

    private String defaultSessionName(String input) {
        String name = input.replaceAll("\\s+", " ").trim();
        return name.length() > 20 ? name.substring(0, 20) + "..." : name;
    }

    /**
     * Returns the agent's current working directory.
     */
    @GetMapping("/workdir")
    public ResponseEntity<Map<String, Object>> workdir() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("workdir", workspace.workDir());

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("code", 200);
        response.put("message", "ok");
        response.put("data", data);
        return ResponseEntity.ok(response);
    }

    /**
     * Switches the agent's working directory and broadcasts the change to all
     * connected SSE clients so every open page stays in sync.
     */
    @PostMapping("/workdir")
    public ResponseEntity<Map<String, Object>> updateWorkdir(@RequestBody Map<String, String> body) {
        String workdir = body == null ? null : body.get("workdir");

        if (workdir == null || workdir.isBlank()) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("code", 400);
            error.put("message", "workdir must not be blank");
            return ResponseEntity.badRequest().body(error);
        }

        try {
            if (workspace instanceof DockerWorkspace docker) {
                // Sandbox workspace: switch the in-container working root (the
                // directory need not exist yet — the agent can create it itself
                // via edit_file / mkdir inside the container).
                docker.setWorkspaceRoot(normalizeContainerPath(workdir));
            } else if (workspace instanceof LocalWorkSpace local) {
                // Local workspace: switch to an existing host directory.
                local.updateWorkDir(workdir);
            } else {
                throw new IllegalArgumentException("workspace does not support switching workdir");
            }
        } catch (IllegalArgumentException e) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("code", 400);
            error.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(error);
        }

        String current = workspace.workDir();
        broadcastWorkdir(current);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("workdir", current);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("code", 200);
        response.put("message", "ok");
        response.put("data", data);
        return ResponseEntity.ok(response);
    }

    /**
     * Normalizes a user-supplied in-container path to an absolute POSIX path ("/app", "app" → "/app").
     */
    private String normalizeContainerPath(String workdir) {
        String p = workdir.trim();
        if (!p.startsWith("/")) {
            p = "/" + p;
        }
        while (p.endsWith("/") && p.length() > 1) {
            p = p.substring(0, p.length() - 1);
        }
        if (!p.matches("/[A-Za-z0-9._\\-/]+")) {
            throw new IllegalArgumentException("invalid container path: " + workdir);
        }
        return p;
    }

    private void broadcastWorkdir(String workdir) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("type", "WORKDIR_CHANGED");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("workdir", workdir);
        event.put("data", data);
        try {
            sseEventPublisher.broadcast(objectMapper.writeValueAsString(event));
        } catch (JsonProcessingException e) {
            log.warn("Failed to broadcast workdir change", e);
        }
    }
}
