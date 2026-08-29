package com.summit.harnessexample;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.summit.harnesscore.runtime.Workspace;
import com.summit.runtime.sandbox.DockerWorkspace;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    private final Workspace workspace = new DockerWorkspace(
            UUID.randomUUID().toString(),
            "6939c6277d8f",
            "/app"
    );
    private final ObjectMapper objectMapper;
    /** In-memory session registry (temporary storage): sessionId -> sessionName. */
    private final Map<String, String> sessions = new ConcurrentHashMap<>();




    /** Opens a Server-Sent Events stream; runtime events are pushed onto it. */
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
        sessions.put(sessionId, sessionName);

        // Run the coding agent asynchronously; events are pushed via SSE.
        String finalSessionId = sessionId;
        CompletableFuture.runAsync(() -> demo.chat(input, streaming, finalSessionId,workspace));

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("input", input);
        data.put("streaming", streaming);
        data.put("sessionId", sessionId);
        data.put("sessionName", sessions.get(sessionId));
        data.put("newSession", newSession);
        data.put("sseClients", sseEventPublisher.connectedCount());

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("code", 200);
        response.put("message", "agent task submitted, events will be streamed via sse://{host}/agent/events");
        response.put("data", data);
        return ResponseEntity.ok(response);
    }

    /** Returns all conversations kept in the in-memory session registry. */
    @GetMapping("/sessions")
    public ResponseEntity<Map<String, Object>> sessions() {
        List<Map<String, Object>> list = new ArrayList<>();
        sessions.forEach((id, name) -> {
            Map<String, Object> session = new LinkedHashMap<>();
            session.put("sessionId", id);
            session.put("sessionName", name);
            list.add(session);
        });

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("sessions", list);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("code", 200);
        response.put("message", "ok");
        response.put("data", data);
        return ResponseEntity.ok(response);
    }

    /** Simple liveness probe for the example app. */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("status", "UP");
        data.put("sessions", sessions.size());
        data.put("sseClients", sseEventPublisher.connectedCount());

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("code", 200);
        response.put("message", "ok");
        response.put("data", data);
        return ResponseEntity.ok(response);
    }

    /** Deletes a conversation from the in-memory session registry. */
    @PostMapping("/sessions/delete")
    public ResponseEntity<Map<String, Object>> deleteSession(@RequestBody Map<String, String> body) {
        String sessionId = body == null ? null : body.get("sessionId");

        if (sessionId == null || sessionId.isBlank()) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("code", 400);
            error.put("message", "sessionId must not be blank");
            return ResponseEntity.badRequest().body(error);
        }
        String removed = sessions.remove(sessionId);
        if (removed == null) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("code", 404);
            error.put("message", "session not found: " + sessionId);
            return ResponseEntity.status(404).body(error);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("sessionId", sessionId);
        data.put("sessionName", removed);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("code", 200);
        response.put("message", "ok");
        response.put("data", data);
        return ResponseEntity.ok(response);
    }

    /** Renames an existing conversation (in-memory map only for now). */
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
        sessions.put(sessionId, sessionName);

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

    /** Returns the agent's current working directory. */
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

        // Switching directories only applies to the local workspace; a sandbox
        // workspace has its fixed in-container root.
        if (!(workspace instanceof LocalWorkSpace local)) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("code", 400);
            error.put("message", "workdir switch is not supported for workspace: " + workspace.id());
            return ResponseEntity.badRequest().body(error);
        }

        try {
            local.updateWorkDir(workdir);
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
