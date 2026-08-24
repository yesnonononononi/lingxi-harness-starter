package com.summit.harnessexample;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Entry point for the simple coding agent.
 *
 * <p>The chat endpoint accepts a user instruction, then submits the execution
 * to a background thread so the HTTP request returns immediately. All runtime
 * events (model messages, tool calls, completion / error) are streamed to the
 * front-end in real-time through the WebSocket channel.</p>
 */
@Slf4j
@RestController
@RequestMapping("/agent")
@RequiredArgsConstructor
public class Controller {

    private final Demo demo;
    private final AgentWebSocketHandler webSocketHandler;

    @PostMapping("/chat")
    public ResponseEntity<Map<String, Object>> chat(@RequestBody Map<String, String> body) {
        String input = body == null ? null : body.get("input");

        if (input == null || input.isBlank()) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("code", 400);
            error.put("message", "input must not be blank");
            return ResponseEntity.badRequest().body(error);
        }

        // Run the coding agent asynchronously; events are pushed via WebSocket.
        CompletableFuture.runAsync(() -> demo.chat(input));

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("input", input);
        data.put("wsClients", webSocketHandler.connectedCount());

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("code", 200);
        response.put("message", "agent task submitted, events will be streamed via ws://{host}/ws/agent");
        response.put("data", data);
        return ResponseEntity.ok(response);
    }
}
