package com.summit.harnesscore.conversation.event;

import com.summit.harnesscore.runtime.RuntimeListener;
import lombok.extern.slf4j.Slf4j;


import java.util.List;

@Slf4j
public class RuntimeEventPublisher {
    private final List<RuntimeListener> listeners;

    public RuntimeEventPublisher(List<RuntimeListener> listeners) {
        this.listeners = listeners;
    }

    public void onExecutionStart(ExecutionStartEvent event){
        try {
            listeners.forEach(listener -> listener.onExecutionStart(event));
        } catch (Exception e) {
            log.error("Error occurred while publishing execution start event", e);
        }
    }
    public void onToolCall(ToolCallStartEvent event){
        try {
            listeners.forEach(listener -> listener.onToolCall(event));
        } catch (Exception e) {
            log.error("Error occurred while publishing tool call event", e);
        }
    }
    public void onToolCallOutput(ToolCallEndEvent event){
        try {
            listeners.forEach(listener -> listener.onToolCallOutput(event));
        } catch (Exception e) {
            log.error("Error occurred while publishing tool call output event", e);
        }

    }
    public void onAiMessage(AgentMessageEvent event){
        try {
            listeners.forEach(listener -> listener.onAiMessage(event));
        } catch (Exception e) {
            log.error("Error occurred while publishing ai message event", e);
        }

    }
    public void onExecutionError(ExecutionErrorEvent event){
        try {
            listeners.forEach(listener -> listener.onExecutionError(event));
        } catch (Exception e) {
            log.error("Error occurred while publishing execution error event", e);
        }

    }
    public void onExecutionComplete(ExecutionCompleteEvent event){
        try {
            listeners.forEach(listener -> listener.onExecutionCompleted(event));
        } catch (Exception e) {
            log.error("Error occurred while publishing execution completed event", e);
        }

    }
}
