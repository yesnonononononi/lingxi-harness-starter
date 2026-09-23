package com.summit.harnessexample;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.summit.core.compact.ContextUsageMetric;
import com.summit.core.conversation.event.*;
import com.summit.core.runtime.RuntimeListener;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/** Listens to all coding-agent runtime events and forwards them to the connected front-end clients through {@link SseEventPublisher}. */
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
        broadcast("EXECUTION_STARTED", event.executionId(), data);
    }

    @Override
    public void onToolCall(ToolCallStartEvent event) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("toolName", event.getToolName());
        data.put("args", event.getArgs());
        broadcast("TOOL_STARTED", event.executionId(), data);
    }

    @Override
    public void onToolCallOutput(ToolCallEndEvent event) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("output", event.getOutput());
        broadcast("TOOL_COMPLETED", event.executionId(), data);
    }

    @Override
    public void onAiMessage(AgentMessageEvent event) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("text", event.getText());
        data.put("thinking", event.getThinking());
        broadcast("AGENT_MESSAGE", event.executionId(), data);
    }

    @Override
    public void onPartialText(AgentPartialTextEvent event) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("text", event.content());
        broadcast("PARTIAL_TEXT", event.executionId(), data);
    }

    @Override
    public void onPartialThinking(AgentPartialThinkingEvent event) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("text", event.content());
        broadcast("PARTIAL_THINKING", event.executionId(), data);
    }

    @Override
    public void onExecutionError(ExecutionErrorEvent event) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("error", event.getErrMsg() == null ? "unknown error" : event.getErrMsg());
        data.put("extraDes", event.getExtraDes());
        broadcast("EXECUTION_FAILED", event.executionId(), data);
    }

    @Override
    public void onExecutionCompleted(ExecutionCompleteEvent event) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("executionId", event.executionId());
        // forward token usage so the front-end can render input / output / total token stats
        if (event.getTokenInfo() != null) {
            data.put("tokenUsage", event.getTokenInfo());
        }
        broadcast("EXECUTION_COMPLETED", event.executionId(), data);
    }

    @Override
    public void onExecutionCancelled(ExecutionCancelledEvent event) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("executionId", event.executionId());
        broadcast("EXECUTION_CANCELLED", event.executionId(), data);
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
        broadcast("CONTEXT_UPDATE", event.executionId(), data);
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
        broadcast("FILE_EDIT", event.getTurnId(), data);
    }

    private void broadcast(String type, String executionId, Map<String, Object> data) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("type", type);
        message.put("executionId", executionId);
        message.put("timestamp", System.currentTimeMillis());
        message.put("data", data);
        try {
            sseEventPublisher.broadcast(objectMapper.writeValueAsString(message));
        } catch (Exception e) {
            log.error("failed to publish sse event type={}", type, e);
        }
    }
}
