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

            listeners.forEach(listener -> {
                try {
                    listener.onExecutionStart(event);
                }catch (Exception e){
                    log.error("Error occurred while publishing execution start event, listenerId:{}",listener.id(), e);
                }
            });
    }

    public void onExecutionResumed(ExecutionResumedEvent executionResumeEvent) {
        listeners.forEach(listener -> {
            try {
                listener.onExecutionResumed(executionResumeEvent);
            } catch (Exception e) {
                log.error("Error occurred while publishing execution resumed event, listenerId:{}", listener.id(), e);
            }
        });
    }

    public void onExecutionSuspended(ExecutionSuspendedEvent executionSuspendedEvent) {
        listeners.forEach(listener -> {
            try {
                listener.onExecutionSuspended(executionSuspendedEvent);
            } catch (Exception e) {
                log.error("Error occurred while publishing execution suspended event, listenerId:{}", listener.id(), e);
            }
        });
    }

    public void onToolCall(ToolCallStartEvent event) {
        listeners.forEach(listener -> {
            try {
                listener.onToolCall(event);
            } catch (Exception e) {
                log.error("Error occurred while publishing tool call event, listenerId:{}", listener.id(), e);
            }
        });
    }

    public void onToolCallOutput(ToolCallEndEvent event) {
        listeners.forEach(listener -> {
            try {
                listener.onToolCallOutput(event);
            } catch (Exception e) {
                log.error("Error occurred while publishing tool call output event, listenerId:{}", listener.id(), e);
            }
        });
    }

    public void onAiMessage(AgentMessageEvent event) {
        listeners.forEach(listener -> {
            try {
                listener.onAiMessage(event);
            } catch (Exception e) {
                log.error("Error occurred while publishing ai message event, listenerId:{}", listener.id(), e);
            }
        });
    }

    public void onExecutionError(ExecutionErrorEvent event) {
        listeners.forEach(listener -> {
            try {
                listener.onExecutionError(event);
            } catch (Exception e) {
                log.error("Error occurred while publishing execution error event, listenerId:{}", listener.id(), e);
            }
        });
    }

    public void onExecutionComplete(ExecutionCompleteEvent event) {
        listeners.forEach(listener -> {
            try {
                listener.onExecutionCompleted(event);
            } catch (Exception e) {
                log.error("Error occurred while publishing execution completed event, listenerId:{}", listener.id(), e);
            }
        });
    }

    public void onExecutionCancelled(ExecutionCancelledEvent event) {
        listeners.forEach(listener -> {
            try {
                listener.onExecutionCancelled(event);
            } catch (Exception e) {
                log.error("Error occurred while publishing execution cancelled event, listenerId:{}", listener.id(), e);
            }
        });
    }

    public void onApplicationEvent(Object event) {
        listeners.forEach(listener -> {
            try {
                listener.onApplicationEvent(event);
            } catch (Exception e) {
                log.error("Error occurred while publishing application event, listenerId:{}", listener.id(), e);
            }
        });
    }

    public void onPartialText(AgentPartialTextEvent event) {
        listeners.forEach(listener -> {
            try {
                listener.onPartialText(event);
            } catch (Exception e) {
                log.error("Error occurred while publishing partial text event, listenerId:{}", listener.id(), e);
            }
        });
    }

    public void onPartialThinking(AgentPartialThinkingEvent event) {
        listeners.forEach(listener -> {
            try {
                listener.onPartialThinking(event);
            } catch (Exception e) {
                log.error("Error occurred while publishing partial thinking event, listenerId:{}", listener.id(), e);
            }
        });
    }

    public void onCompleteText(AgentCompleteTextEvent event) {
        listeners.forEach(listener -> {
            try {
                listener.onCompleteText(event);
            } catch (Exception e) {
                log.error("Error occurred while publishing complete text event, listenerId:{}", listener.id(), e);
            }
        });
    }

    public void onContextUpdate(ContextUpdateEvent event) {
        listeners.forEach(listener -> {
            try {
                listener.onContextUpdate(event);
            } catch (Exception e) {
                log.error("Error occurred while publishing context update event, listenerId:{}", listener.id(), e);
            }
        });
    }


}
