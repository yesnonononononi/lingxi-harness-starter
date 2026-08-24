package com.summit.harnessexample;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.summit.harnesscore.conversation.event.AgentMessageEvent;
import com.summit.harnesscore.conversation.event.ExecutionCompleteEvent;
import com.summit.harnesscore.conversation.event.ExecutionErrorEvent;
import com.summit.harnesscore.conversation.event.ExecutionStartEvent;
import com.summit.harnesscore.conversation.event.ToolCallEndEvent;
import com.summit.harnesscore.conversation.event.ToolCallStartEvent;
import com.summit.harnesscore.runtime.RuntimeListener;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Listens to all coding-agent runtime events and forwards them to the
 * connected front-end clients through {@link AgentWebSocketHandler}.
 *
 * <p>Each event is serialized into the following JSON envelope:</p>
 * <pre>
 * {
 *   "type": "AGENT_MESSAGE" | "TOOL_STARTED" | "TOOL_COMPLETED" |
 *           "EXECUTION_STARTED" | "EXECUTION_COMPLETED" | "EXECUTION_FAILED",
 *   "executionId": "...",
 *   "timestamp": 1234567890,
 *   "data": { ... }
 * }
 * </pre>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EventListener implements RuntimeListener {

    private final AgentWebSocketHandler webSocketHandler;
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
        broadcast("AGENT_MESSAGE", event.executionId(), data);
    }

    @Override
    public void onExecutionError(ExecutionErrorEvent event) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("error", event.getErr() == null ? "unknown error" : event.getErr().getMessage());
        data.put("extraDes", event.getExtraDes());
        broadcast("EXECUTION_FAILED", event.executionId(), data);
    }

    @Override
    public void onExecutionCompleted(ExecutionCompleteEvent event) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("executionId", event.executionId());
        broadcast("EXECUTION_COMPLETED", event.executionId(), data);
    }

    private void broadcast(String type, String executionId, Map<String, Object> data) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("type", type);
        message.put("executionId", executionId);
        message.put("timestamp", System.currentTimeMillis());
        message.put("data", data);
        try {
            webSocketHandler.broadcast(objectMapper.writeValueAsString(message));
        } catch (Exception e) {
            log.error("failed to publish ws event type={}", type, e);
        }
    }
}
