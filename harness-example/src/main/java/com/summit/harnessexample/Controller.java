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
import com.summit.core.tool.CommandConfirmGate;
import com.summit.core.tool.CommandConfirmLevel;
import com.summit.core.tool.CommandConfirmRegistry;
import com.summit.core.tool.CommandDecision;
import com.summit.core.tool.LoopBoundary;
import com.summit.core.tool.PlanApprovalGate;
import com.summit.core.tool.PlanApprovalRegistry;
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
     * Resolves the currently active workspace (local or Docker sandbox). The
     * "select workspace" flow (WorkspaceSandboxService) may swap it globally.
     */
    private final ActiveWorkspace activeWorkspace;
    private final ObjectMapper objectMapper;
    /**
     * Per-session lifecycle command registry. Every execution registers a fresh,
     * dedicated command store under its sessionId (see DefaultRuntimeFactory), so
     * pause/resume/stop issued here target exactly that session/execution and can
     * never leak into another execution's queue.
     */
    private final LifeStyleCommandRegistry lifeStyleCommandRegistry;
    /**
     * Registry of commands awaiting human approval; the approve/reject endpoints
     * write their decisions into it by toolExecutionId.
     */
    private final CommandConfirmRegistry commandConfirmRegistry;
    /**
     * Registry of plans awaiting human approval; the approve/reject endpoints
     * write their decisions into it by plan execution id.
     */
    private final PlanApprovalRegistry planApprovalRegistry;
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
        CommandConfirmLevel commandConfirmLevel = parseCommandConfirmLevel(body);
        String systemPrompt = body == null ? null : body.get("systemPrompt");
        LoopBoundary loopBoundary = parseLoopBoundary(body);

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
        CompletableFuture<Void> task = CompletableFuture.runAsync(() -> demo.chat(input, streaming, finalSessionId, finalSessionName, activeWorkspace.get(), commandConfirmLevel, systemPrompt, loopBoundary));
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

    /**
     * Approves a command that is waiting for human approval. Once the decision is
     * written, the agent loop thread is woken up and the command executes for real
     * without interception; the result is returned to the model as usual.
     */
    @PostMapping("/commands/{toolExecutionId}/approve")
    public ResponseEntity<Map<String, Object>> approveCommand(@PathVariable("toolExecutionId") String toolExecutionId) {
        return decideCommand(toolExecutionId, CommandDecision.APPROVE);
    }

    /**
     * Rejects a command that is waiting for human approval. The command never runs
     * and the agent loop continues with the rejection reason.
     */
    @PostMapping("/commands/{toolExecutionId}/reject")
    public ResponseEntity<Map<String, Object>> rejectCommand(@PathVariable("toolExecutionId") String toolExecutionId) {
        return decideCommand(toolExecutionId, CommandDecision.REJECT);
    }

    /**
     * Approves a plan that is waiting for human approval (PLANING mode). Once the
     * decision is written, the agent loop thread is woken up and implements the
     * plan under the EXECUTE boundary for real.
     */
    @PostMapping("/plans/{planExecutionId}/approve")
    public ResponseEntity<Map<String, Object>> approvePlan(@PathVariable("planExecutionId") String planExecutionId) {
        return decidePlan(planExecutionId, CommandDecision.APPROVE, null);
    }

    /**
     * Rejects a plan that is waiting for human approval. The plan is never
     * implemented: the agent loop finishes without running any write tool, so the
     * user can follow up with a new instruction / revised plan.
     */
    @PostMapping("/plans/{planExecutionId}/reject")
    public ResponseEntity<Map<String, Object>> rejectPlan(@PathVariable("planExecutionId") String planExecutionId,
            @RequestBody(required = false) Map<String, Object> body) {
        String reason = body == null ? null
                : (body.get("reason") == null ? null : String.valueOf(body.get("reason")));
        return decidePlan(planExecutionId, CommandDecision.REJECT, reason);
    }

    private ResponseEntity<Map<String, Object>> decideCommand(String toolExecutionId, CommandDecision decision) {
        Map<String, Object> data = new LinkedHashMap<>();
        CommandConfirmGate gate = commandConfirmRegistry.get(toolExecutionId);
        if (gate == null) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("code", 404);
            error.put("message", "no pending command found for toolExecutionId: " + toolExecutionId);
            error.put("data", Map.of("toolExecutionId", toolExecutionId, "pendingCommands", commandConfirmRegistry.size()));
            return ResponseEntity.status(404).body(error);
        }
        if (!gate.isPending()) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("code", 409);
            error.put("message", "command already decided: " + gate.getDecision());
            error.put("data", Map.of("toolExecutionId", toolExecutionId, "command", gate.getCommand(), "decision", String.valueOf(gate.getDecision())));
            return ResponseEntity.status(409).body(error);
        }
        boolean applied = commandConfirmRegistry.decide(toolExecutionId, decision);
        if (!applied) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("code", 409);
            error.put("message", "command decision failed, it may have already been decided");
            error.put("data", Map.of("toolExecutionId", toolExecutionId));
            return ResponseEntity.status(409).body(error);
        }
        data.put("code", 200);
        data.put("message", "decision recorded: " + decision.name() + ", agent loop will be woken up");
        data.put("data", Map.of("toolExecutionId", toolExecutionId, "command", gate.getCommand(),
                "decision", decision.name(), "pendingCommands", commandConfirmRegistry.size()));
        return ResponseEntity.ok(data);
    }

    private ResponseEntity<Map<String, Object>> decidePlan(String planExecutionId, CommandDecision decision, String rejectReason) {
        Map<String, Object> data = new LinkedHashMap<>();
        PlanApprovalGate gate = planApprovalRegistry.get(planExecutionId);
        if (gate == null) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("code", 404);
            error.put("message", "no pending plan approval found for planExecutionId: " + planExecutionId);
            error.put("data", Map.of("planExecutionId", planExecutionId, "pendingPlans", planApprovalRegistry.size()));
            return ResponseEntity.status(404).body(error);
        }
        if (!gate.isPending()) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("code", 409);
            error.put("message", "plan already decided: " + gate.getDecision());
            error.put("data", Map.of("planExecutionId", planExecutionId,
                    "title", String.valueOf(gate.getPlanTitle()),
                    "decision", String.valueOf(gate.getDecision())));
            return ResponseEntity.status(409).body(error);
        }
        boolean applied = planApprovalRegistry.decide(planExecutionId, decision);
        if (!applied) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("code", 409);
            error.put("message", "plan decision failed, it may have already been decided");
            error.put("data", Map.of("planExecutionId", planExecutionId));
            return ResponseEntity.status(409).body(error);
        }
        // Attach the optional rejection reason after the REJECT was actually written.
        if (decision == CommandDecision.REJECT && rejectReason != null && !rejectReason.isBlank()) {
            gate.setRejectReason(rejectReason);
        }
        data.put("code", 200);
        data.put("message", "plan decision recorded: " + decision.name() + ", agent loop will be woken up");
        data.put("data", Map.of("planExecutionId", planExecutionId,
                "title", String.valueOf(gate.getPlanTitle()),
                "decision", decision.name(),
                "rejectReason", decision == CommandDecision.REJECT ? gate.getRejectReason() : null,
                "pendingPlans", planApprovalRegistry.size()));
        return ResponseEntity.ok(data);
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
        data.put("workdir", activeWorkspace.get().workDir());

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

        Workspace current = activeWorkspace.get();
        try {
            if (current instanceof DockerWorkspace docker) {
                // Sandbox workspace: switch the in-container working root (the
                // directory need not exist yet — the agent can create it itself
                // via edit_file / mkdir inside the container).
                docker.setWorkspaceRoot(normalizeContainerPath(workdir));
            } else if (current instanceof LocalWorkSpace local) {
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

        String workdirNow = activeWorkspace.get().workDir();
        broadcastWorkdir(workdirNow);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("workdir", workdirNow);

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

    private CommandConfirmLevel parseCommandConfirmLevel(Map<String, String> body) {
        String value = body == null ? null : body.get("commandConfirmLevel");
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return CommandConfirmLevel.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Parses the optional {@code loopBoundary} field (PLANING / EXECUTE) leniently:
     * an illegal or absent value is ignored and treated as the default EXECUTE mode.
     */
    private LoopBoundary parseLoopBoundary(Map<String, String> body) {
        String value = body == null ? null : body.get("loopBoundary");
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LoopBoundary.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
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
