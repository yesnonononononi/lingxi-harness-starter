package com.summit.harnessexample;

import com.summit.harnessexample.common.Result;
import com.summit.harnessexample.dto.ChatRequest;
import com.summit.harnessexample.dto.WorkdirRequest;
import com.summit.harnessexample.service.AgentChatService;
import com.summit.harnessexample.service.SessionService;
import com.summit.harnessexample.service.WorkdirService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Entry point of the coding agent.
 *
 * <p>Thin HTTP layer only: it maps the request to a DTO, delegates to a service and wraps
 * the payload into the unified {@link Result} envelope. Submitting a chat returns
 * immediately; all runtime events stream through the SSE channel
 * ({@code GET /agent/events}).</p>
 */
@Slf4j
@RestController
@RequestMapping("/agent")
@RequiredArgsConstructor
public class AgentController {

    private final AgentChatService agentChatService;
    private final WorkdirService workdirService;
    private final SessionService sessionService;
    private final SseEventPublisher sseEventPublisher;

    /** Opens the Server-Sent Events stream that carries every runtime event. */
    @GetMapping(value = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events() {
        return sseEventPublisher.connect();
    }

    /** Submits one user instruction and returns immediately. */
    @PostMapping("/chat")
    public Result<Map<String, Object>> chat(@RequestBody(required = false) ChatRequest request) {
        return Result.ok("agent task submitted, events will be streamed via sse://{host}/agent/events",
                agentChatService.chat(request));
    }

    /** Best-effort cancellation of one session's tracked tasks, or all tasks when no session is given. */
    @PostMapping("/stop")
    public Result<Map<String, Object>> stop(@RequestParam(value = "sessionId", required = false) String sessionId) {
        return Result.ok(agentChatService.stop(sessionId));
    }

    /** Returns whether one session currently has an in-flight task. */
    @GetMapping("/executions/status")
    public Result<Map<String, Object>> executionStatus(
            @RequestParam("sessionId") String sessionId) {
        return Result.ok(agentChatService.status(sessionId));
    }

    /** The agent's current working directory. */
    @GetMapping("/workdir")
    public Result<Map<String, Object>> workdir() {
        return Result.ok(workdirService.current());
    }

    /** Switches the working directory and broadcasts the change to all SSE clients. */
    @PostMapping("/workdir")
    public Result<Map<String, Object>> updateWorkdir(@RequestBody(required = false) WorkdirRequest request) {
        return Result.ok(workdirService.update(request == null ? null : request.workdir()));
    }

    /** Liveness probe with a few runtime counters. */
    @GetMapping("/health")
    public Result<Map<String, Object>> health() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("status", "UP");
        data.put("sessions", sessionService.count());
        data.put("sseClients", sseEventPublisher.connectedCount());
        data.put("runningSessions", agentChatService.runningCount());
        return Result.ok(data);
    }
}
