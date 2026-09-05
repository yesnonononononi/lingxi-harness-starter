package com.summit.harnessexample;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.summit.core.compact.ContextUsageMetric;
import com.summit.core.conversation.event.*;
import com.summit.core.plan.PlanDecision;
import com.summit.core.plan.PlanState;
import com.summit.core.plan.PlanStep;
import com.summit.core.runtime.RuntimeListener;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.Serializable;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Listens to all coding-agent runtime events and forwards them to the
 * connected front-end clients through {@link SseEventPublisher}.
 *
 * <p>Each event is serialized into the following JSON envelope:</p>
 * <pre>
 * {
 *   "type": "AGENT_MESSAGE" | "TOOL_STARTED" | "TOOL_COMPLETED" |
 *           "EXECUTION_STARTED" | "EXECUTION_COMPLETED" | "EXECUTION_FAILED",
 *   "executionId": "...",
 *   "sessionId": "...",
 *   "timestamp": 1234567890,
 *   "data": { ... }
 * }
 * </pre>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EventListener implements RuntimeListener {

    private final SseEventPublisher sseEventPublisher;
    private final ObjectMapper objectMapper;

    @Override
    public void onExecutionStart(ExecutionStartEvent event) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("executionId", event.executionId());
        broadcast("EXECUTION_STARTED", event.executionId(), event.getSessionId(), data);
    }

    @Override
    public void onToolCall(ToolCallStartEvent event) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("toolName", event.getToolName());
        data.put("args", event.getArgs());
        broadcast("TOOL_STARTED", event.executionId(), event.getSessionId(), data);
    }

    @Override
    public void onToolCallOutput(ToolCallEndEvent event) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("output", event.getOutput());
        broadcast("TOOL_COMPLETED", event.executionId(), event.getSessionId(), data);
    }

    @Override
    public void onAiMessage(AgentMessageEvent event) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("text", event.getText());
        data.put("thinking", event.getThinking());
        broadcast("AGENT_MESSAGE", event.executionId(), event.getSessionId(), data);
    }

    @Override
    public void onPartialText(AgentPartialTextEvent event) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("text", event.content());
        broadcast("PARTIAL_TEXT", event.executionId(), event.sessionId(), data);
    }

    @Override
    public void onPartialThinking(AgentPartialThinkingEvent event) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("text", event.content());
        broadcast("PARTIAL_THINKING", event.executionId(), event.sessionId(), data);
    }

    @Override
    public void onWaitCommandCheck(WaitCommandCheckEvent waitCommandCheckEvent) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("toolExecutionId", waitCommandCheckEvent.getToolExecutionId());
        data.put("command", waitCommandCheckEvent.getFormatedToolCommand());
        data.put("status", "PENDING");
        data.put("approveUrl", "/agent/commands/" + waitCommandCheckEvent.getToolExecutionId() + "/approve");
        data.put("rejectUrl", "/agent/commands/" + waitCommandCheckEvent.getToolExecutionId() + "/reject");
        broadcast("WAIT_COMMAND_CHECK", waitCommandCheckEvent.getExecutionId(), waitCommandCheckEvent.getSessionId(), data);
    }

    @Override
    public void onExecutionError(ExecutionErrorEvent event) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("error", event.getErr() == null ? "unknown error" : event.getErr().getMessage());
        data.put("extraDes", event.getExtraDes());
        broadcast("EXECUTION_FAILED", event.executionId(), event.getSessionId(), data);
    }

    @Override
    public void onExecutionCompleted(ExecutionCompleteEvent event) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("executionId", event.executionId());
        // forward token usage so the front-end can render input / output / total token stats
        if (event.getTokenInfo() != null) {
            data.put("tokenUsage", event.getTokenInfo());
        }
        broadcast("EXECUTION_COMPLETED", event.executionId(), event.getSessionId(), data);
    }

    @Override
    public void onExecutionCancelled(ExecutionCancelledEvent event) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("executionId", event.executionId());
        broadcast("EXECUTION_CANCELLED", event.executionId(), event.getSessionId(), data);
    }

    @Override
    public void onPlanDecision(PlanDecisionEvent event) {
        PlanDecision decision = event.getPlanDecision();
        Map<String, Object> data = new LinkedHashMap<>();
        if (decision != null) {
            data.put("title", decision.title());
            List<Map<String, Object>> steps = decision.steps() == null
                    ? List.of()
                    : decision.steps().stream().map(step -> {
                        Map<String, Object> stepJson = new LinkedHashMap<>();
                        stepJson.put("id", step.id());
                        stepJson.put("description", step.description());
                        stepJson.put("status", step.status() == null ? null : step.status().name());
                        return stepJson;
                    }).toList();
            data.put("steps", steps);
        }
        // The plan now waits for human approval; expose the approve/reject URLs so
        // the front-end plan card can call them (same pattern as WAIT_COMMAND_CHECK).
        String executionId = event.executionId();
        data.put("approveUrl", executionId == null ? null : "/agent/plans/" + executionId + "/approve");
        data.put("rejectUrl", executionId == null ? null : "/agent/plans/" + executionId + "/reject");
        // A freshly captured plan is always UN_APPROVED (waiting for the human decision).
        data.put("state", PlanState.UN_APPROVED.name());
        data.put("stateLabel", PlanState.UN_APPROVED.getLabel());
        broadcast("PLAN_DECISION", executionId, event.getSessionId(), data);
    }

    @Override
    public void onContextUpdate(ContextUpdateEvent event) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("phase", event.getPhase() == null ? null : event.getPhase().name());
        ContextUsageMetric usage = event.getUsage();
        if (usage != null) {
            data.put("tokenCount", usage.tokenCount());
            data.put("maxTokens", usage.maxTokens());
            data.put("ratio", usage.ratio());
        }
        data.put("message", event.getMessage());
        broadcast("CONTEXT_UPDATE", event.executionId(), event.getSessionId(), data);
    }

    @Override
    public void onFileEdit(FileEditEvent event) {
        // The file content is already written by the tool itself; this event
        // only carries the applied edit to the front-end for its diff view and
        // the pending accept/reject decision.
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("recordId", event.getRecordId());
        data.put("turnId", event.getTurnId());
        data.put("filePath", event.getFilePath());
        data.put("oldContent", event.getOldContent());
        data.put("newContent", event.getNewContent());
        data.put("plusLines", event.getPlusLines());
        data.put("minusLines", event.getMinusLines());
        broadcast("FILE_EDIT", null, event.getSessionId(), data);
    }

    private void broadcast(String type, String executionId, Serializable sessionId, Map<String, Object> data) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("type", type);
        message.put("executionId", executionId);
        message.put("sessionId", sessionId);
        message.put("timestamp", System.currentTimeMillis());
        message.put("data", data);
        try {
            sseEventPublisher.broadcast(objectMapper.writeValueAsString(message));
        } catch (Exception e) {
            log.error("failed to publish sse event type={}", type, e);
        }
    }
}
