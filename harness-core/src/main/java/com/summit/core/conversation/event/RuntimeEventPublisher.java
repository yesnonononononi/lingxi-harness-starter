package com.summit.core.conversation.event;

import com.summit.core.runtime.RuntimeListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;


public class RuntimeEventPublisher {
    private static final Logger log = LoggerFactory.getLogger(RuntimeEventPublisher.class);
    private final List<RuntimeListener> listeners;

    public RuntimeEventPublisher(List<RuntimeListener> listeners) {
        this.listeners = listeners;
    }

    public void onExecutionStart(ExecutionStartEvent event) {
        try {
            listeners.forEach(listener -> listener.onExecutionStart(event));
        } catch (Exception e) {
            log.error("Error occurred while publishing execution start event", e);
        }
    }

    public void onCommandCheck(WaitCommandCheckEvent waitCommandCheckEvent){
        try {
            listeners.forEach(listener -> listener.onWaitCommandCheck(waitCommandCheckEvent));
        }catch (Exception e){
            log.error("Error occurred while publishing execution command tool check event", e);
        }
    }


    public void onToolCall(ToolCallStartEvent event) {
        try {
            listeners.forEach(listener -> listener.onToolCall(event));
        } catch (Exception e) {
            log.error("Error occurred while publishing tool call event", e);
        }
    }

    public void onToolCallOutput(ToolCallEndEvent event) {
        try {
            listeners.forEach(listener -> listener.onToolCallOutput(event));
        } catch (Exception e) {
            log.error("Error occurred while publishing tool call output event", e);
        }

    }

    public void onAiMessage(AgentMessageEvent event) {
        try {
            listeners.forEach(listener -> listener.onAiMessage(event));
        } catch (Exception e) {
            log.error("Error occurred while publishing ai message event", e);
        }

    }

    public void onExecutionError(ExecutionErrorEvent event) {
        try {
            listeners.forEach(listener -> listener.onExecutionError(event));
        } catch (Exception e) {
            log.error("Error occurred while publishing execution error event", e);
        }

    }

    public void onExecutionComplete(ExecutionCompleteEvent event) {
        try {
            listeners.forEach(listener -> listener.onExecutionCompleted(event));
        } catch (Exception e) {
            log.error("Error occurred while publishing execution completed event", e);
        }

    }

    public void onExecutionCancelled(ExecutionCancelledEvent event) {
        try {
            listeners.forEach(listener -> listener.onExecutionCancelled(event));
        } catch (Exception e) {
            log.error("Error occurred while publishing execution cancelled event", e);
        }
    }

    public void onFileEdit(FileEditEvent event) {
        try {
            listeners.forEach(listener -> listener.onFileEdit(event));
        } catch (Exception e) {
            log.error("Error occurred while publishing file edit event", e);
        }
    }

    public void onPartialText(AgentPartialTextEvent event) {
        try {
            listeners.forEach(listener -> listener.onPartialText(event));
        } catch (Exception e) {
            log.error("Error occurred while publishing partial text event", e);
        }
    }

    public void onPartialThinking(AgentPartialThinkingEvent event) {
        try {
            listeners.forEach(listener -> listener.onPartialThinking(event));
        } catch (Exception e) {
            log.error("Error occurred while publishing partial thinking event", e);
        }
    }

    public void onPlanDecision(PlanDecisionEvent event) {
        try {
            listeners.forEach(listener -> listener.onPlanDecision(event));
        } catch (Exception e) {
            log.error("Error occurred while publishing plan decision event", e);
        }
    }

    public void onContextUpdate(ContextUpdateEvent event) {
        try {
            listeners.forEach(listener -> listener.onContextUpdate(event));
        } catch (Exception e) {
            log.error("Error occurred while publishing context update event", e);
        }
    }
}
