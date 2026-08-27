package com.summit.harnessexample;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

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
    private final LocalWorkSpace workSpace;
    private final ObjectMapper objectMapper;

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

        // Run the coding agent asynchronously; events are pushed via SSE.
        CompletableFuture.runAsync(() -> demo.chat(input, true));

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("input", input);
        data.put("streaming", streaming);
        data.put("sseClients", sseEventPublisher.connectedCount());

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("code", 200);
        response.put("message", "agent task submitted, events will be streamed via sse://{host}/agent/events");
        response.put("data", data);
        return ResponseEntity.ok(response);
    }

    /** Returns the agent's current working directory. */
    @GetMapping("/workdir")
    public ResponseEntity<Map<String, Object>> workdir() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("workdir", workSpace.workDir());

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
            workSpace.updateWorkDir(workdir);
        } catch (IllegalArgumentException e) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("code", 400);
            error.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(error);
        }

        String current = workSpace.workDir();
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
