package com.summit.harnessexample.service;

import com.summit.core.runtime.LifeStyleCommandRegistry;
import com.summit.core.tool.CommandConfirmLevel;
import com.summit.core.tool.LoopBoundary;
import com.summit.harnessexample.ActiveWorkspace;
import com.summit.harnessexample.Demo;
import com.summit.harnessexample.SseEventPublisher;
import com.summit.harnessexample.common.ApiException;
import com.summit.harnessexample.dto.ChatRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Orchestration of the agent execution lifecycle: submitting a chat run asynchronously
 * and issuing lifecycle control commands (pause / resume / stop).
 *
 * <p>The HTTP layer only maps request / response; everything about session resolution,
 * the in-flight task registry and the lifecycle registry lives here.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentChatService {

    private final Demo demo;
    private final SseEventPublisher sseEventPublisher;
    private final ActiveWorkspace activeWorkspace;
    private final LifeStyleCommandRegistry lifeStyleCommandRegistry;

    /** In-flight agent tasks (sessionId -> future), so a run can be stopped and counted. */
    private final Map<String, CompletableFuture<Void>> runningTasks = new ConcurrentHashMap<>();

    /**
     * Submits one user instruction to the agent on a background thread and returns
     * immediately; every runtime event is pushed through SSE.
     */
    public Map<String, Object> chat(ChatRequest request) {
        String input = request == null ? null : request.input();
        if (input == null || input.isBlank()) {
            throw ApiException.badRequest("input must not be blank");
        }
        boolean streaming = request.streaming() != null && request.streaming();
        CommandConfirmLevel commandConfirmLevel = parseCommandConfirmLevel(request.commandConfirmLevel());
        LoopBoundary loopBoundary = parseLoopBoundary(request.loopBoundary());
        String systemPrompt = request.systemPrompt();

        // Resolve the conversation: reuse an existing sessionId or create a new one.
        String sessionId = request.sessionId();
        boolean newSession = sessionId == null || sessionId.isBlank();
        if (newSession) {
            sessionId = UUID.randomUUID().toString();
        }
        String sessionName = request.sessionName();
        if (sessionName == null || sessionName.isBlank()) {
            sessionName = SessionService.defaultSessionName(input);
        }

        // Run the coding agent asynchronously; events are pushed via SSE.
        String finalSessionId = sessionId;
        String finalSessionName = sessionName;
        CompletableFuture<Void> task = CompletableFuture.runAsync(() -> demo.chat(input, streaming,
                finalSessionId, finalSessionName, activeWorkspace.get(), commandConfirmLevel, systemPrompt, loopBoundary));
        runningTasks.put(finalSessionId, task);
        task.whenComplete((result, error) -> runningTasks.remove(finalSessionId, task));

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("input", input);
        data.put("streaming", streaming);
        data.put("commandConfirmLevel", commandConfirmLevel == null ? null : commandConfirmLevel.name());
        data.put("systemPrompt", systemPrompt);
        data.put("loopBoundary", loopBoundary == null ? null : loopBoundary.name());
        data.put("sessionId", sessionId);
        data.put("sessionName", sessionName);
        data.put("newSession", newSession);
        data.put("sseClients", sseEventPublisher.connectedCount());
        data.put("runningSessions", runningTasks.size());
        return data;
    }

    /**
     * Applies a lifecycle command. Without {@code sessionId} it targets every running
     * session; with one it targets exactly that session (and fails with 404 otherwise,
     * so a stale page never enqueues a command that would leak into a later run).
     */
    public Map<String, Object> control(String action, String sessionId) {
        if (sessionId != null && !runningTasks.containsKey(sessionId)) {
            throw ApiException.notFound("no running execution for sessionId: " + sessionId,
                    Map.of("sessionId", sessionId, "runningSessions", runningTasks.size()));
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("action", action);
        data.put("sessionId", sessionId);

        if (sessionId == null && runningTasks.isEmpty()) {
            // No live agent-loop: do NOT enqueue the command, it would leak into the
            // next execution started later.
            data.put("runningSessions", 0);
            data.put("applied", false);
            data.put("message", "no running agent execution, command ignored");
            return data;
        }

        if ("stop".equals(action) && sessionId != null) {
            // Interrupt the loop thread; the checkpointer turns the interrupt into a
            // CANCELLED state while paused, otherwise it is best-effort.
            runningTasks.get(sessionId).cancel(true);
        }

        applyToRegistry(action, sessionId);
        data.put("runningSessions", runningTasks.size());
        data.put("applied", true);
        return data;
    }

    /** Whether any agent run is currently in flight. */
    public int runningCount() {
        return runningTasks.size();
    }

    // ------------------------------------------------------------------ private

    private void applyToRegistry(String action, String sessionId) {
        if (sessionId != null) {
            switch (action) {
                case "pause" -> lifeStyleCommandRegistry.pause(sessionId);
                case "resume" -> lifeStyleCommandRegistry.resume(sessionId);
                case "stop" -> lifeStyleCommandRegistry.stop(sessionId);
                default -> throw ApiException.badRequest("unsupported control action: " + action);
            }
            return;
        }
        switch (action) {
            case "pause" -> lifeStyleCommandRegistry.pauseAll();
            case "resume" -> lifeStyleCommandRegistry.resumeAll();
            case "stop" -> lifeStyleCommandRegistry.stopAll();
            default -> throw ApiException.badRequest("unsupported control action: " + action);
        }
    }

    /** Lenient parse: an illegal or absent value is ignored (the runtime applies its own default). */
    private CommandConfirmLevel parseCommandConfirmLevel(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return CommandConfirmLevel.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Lenient parse of the optional PLANING / EXECUTE boundary. */
    private LoopBoundary parseLoopBoundary(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LoopBoundary.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
