package com.summit.harnessexample.service;

import com.summit.harnessexample.CommandApprovalPolicy;
import com.summit.harnessexample.ActiveWorkspace;
import com.summit.harnessexample.Demo;
import com.summit.harnessexample.SseEventPublisher;
import com.summit.harnessexample.common.ApiException;
import com.summit.harnessexample.dto.ChatRequest;
import com.summit.core.agent.Execution;
import com.summit.core.conversation.message.Message;
import com.summit.core.conversation.message.UserMessageEntity;
import com.summit.harnessexample.session_policy.ConversationRecord;
import com.summit.harnessexample.session_policy.RedisConversationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/** Orchestration of asynchronous agent executions and best-effort task cancellation. */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentChatService {

    private final Demo demo;
    private final SseEventPublisher sseEventPublisher;
    private final ActiveWorkspace activeWorkspace;
    private final RedisConversationRepository conversationRepository;

    /** In-flight agent tasks grouped by the application's conversation id. */
    private final Map<String, Set<CompletableFuture<Void>>> runningTasks = new ConcurrentHashMap<>();

    /** Submits one user instruction to the agent on a background thread and returns immediately; every runtime event is pushed through SSE. */
    public Map<String, Object> chat(ChatRequest request) {
        String input = request == null ? null : request.input();
        if (input == null || input.isBlank()) {
            throw ApiException.badRequest("input must not be blank");
        }
        String modelProvider = request.modelProvider();
        CommandApprovalPolicy approvalPolicy = parseCommandApprovalPolicy(request.commandApprovalPolicy());
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
        String executionId = UUID.randomUUID().toString();
        List<Message> context = new ArrayList<>(conversationRepository.find(finalSessionId)
                .map(ConversationRecord::messages).orElseGet(List::of));
        context.add(UserMessageEntity.from(input));
        CompletableFuture<Void> task = CompletableFuture.runAsync(() -> {
            Execution execution = demo.chat(context, executionId, modelProvider,
                    activeWorkspace.get(), approvalPolicy, systemPrompt);
            conversationRepository.save(new ConversationRecord(finalSessionId, finalSessionName,
                    execution.getMessages()));
        });
        runningTasks.compute(finalSessionId, (key, tasks) -> {
            Set<CompletableFuture<Void>> registry = tasks == null ? ConcurrentHashMap.newKeySet() : tasks;
            registry.add(task);
            return registry;
        });
        task.whenComplete((result, error) -> {
            runningTasks.computeIfPresent(finalSessionId, (key, tasks) -> {
                tasks.remove(task);
                return tasks.isEmpty() ? null : tasks;
            });
        });

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("input", input);
        data.put("modelProvider", modelProvider);
        data.put("commandApprovalPolicy", approvalPolicy.name());
        data.put("systemPrompt", systemPrompt);
        data.put("sessionId", sessionId);
        data.put("executionId", executionId);
        data.put("sessionName", sessionName);
        data.put("newSession", newSession);
        data.put("sseClients", sseEventPublisher.connectedCount());
        data.put("runningSessions", runningTasks.size());
        return data;
    }

    /** Cancels the tracked tasks of one session, or every tracked task when {@code sessionId} is absent. */
    public Map<String, Object> stop(String sessionId) {
        if (sessionId != null && !runningTasks.containsKey(sessionId)) {
            throw ApiException.notFound("no running execution for sessionId: " + sessionId,
                    Map.of("sessionId", sessionId, "runningSessions", runningTasks.size()));
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("action", "stop");
        data.put("sessionId", sessionId);

        if (sessionId == null && runningTasks.isEmpty()) {
            data.put("runningSessions", 0);
            data.put("applied", false);
            data.put("message", "no running agent execution, command ignored");
            return data;
        }

        cancelTasks(sessionId);
        data.put("runningSessions", runningTasks.size());
        data.put("applied", true);
        return data;
    }

    /** Number of sessions with at least one in-flight agent run. */
    public int runningCount() {
        return runningTasks.size();
    }

    /** Live task state used when the UI returns to an already-running session. */
    public Map<String, Object> status(String sessionId) {
        boolean running = sessionId != null && runningTasks.containsKey(sessionId);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("sessionId", sessionId);
        data.put("running", running);
        return data;
    }

    // ------------------------------------------------------------------ private

    /** Interrupts the in-flight runs of one session, or of every session when {@code sessionId} is null. */
    private void cancelTasks(String sessionId) {
        if (sessionId != null) {
            runningTasks.getOrDefault(sessionId, Set.of()).forEach(task -> task.cancel(true));
            return;
        }
        runningTasks.values().forEach(tasks -> tasks.forEach(task -> task.cancel(true)));
    }

    /** Lenient parse: an illegal or absent value falls back to this application's own default. */
    private CommandApprovalPolicy parseCommandApprovalPolicy(String value) {
        if (value == null || value.isBlank()) {
            return CommandApprovalPolicy.DEFAULT;
        }
        try {
            return CommandApprovalPolicy.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return CommandApprovalPolicy.DEFAULT;
        }
    }

}
