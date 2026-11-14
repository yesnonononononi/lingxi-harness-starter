package com.summit.core.conversation.event;


import com.summit.core.runtime.RuntimeListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;


public class RuntimeEventPublisher {
    private static final Logger log = LoggerFactory.getLogger(RuntimeEventPublisher.class);
    /** Sorted once: a listener states its dispatch position, and nothing reorders the chain later. */
    private final List<RuntimeListener> listeners;

    public RuntimeEventPublisher(List<RuntimeListener> listeners) {
        this.listeners = listeners == null ? List.of() : listeners.stream()
                .sorted(Comparator.comparingInt(RuntimeListener::order))
                .toList();
    }

    public void onExecutionStart(ExecutionStartEvent event) {
        publish("execution start", RuntimeListener::onExecutionStartPolicy,
                listener -> listener.onExecutionStart(event));
    }

    public void onExecutionResumed(ExecutionResumedEvent executionResumeEvent) {
        publish("execution resumed", RuntimeListener::onExecutionResumedPolicy,
                listener -> listener.onExecutionResumed(executionResumeEvent));
    }

    public void onExecutionSuspended(ExecutionSuspendedEvent executionSuspendedEvent) {
        publish("execution suspended", RuntimeListener::onExecutionSuspendedPolicy,
                listener -> listener.onExecutionSuspended(executionSuspendedEvent));
    }

    public void onToolCallPreparing(ToolCallPrepareEvent event){
        publish("tool call prepare", RuntimeListener::onToolCallPreparePolicy,
                listener -> listener.onToolCallPrepare(event));
    }

    public void onToolCall(ToolCallStartEvent event) {
        publish("tool call", RuntimeListener::onToolCallPolicy,
                listener -> listener.onToolCall(event));
    }

    public void onToolCallOutput(ToolCallEndEvent event) {
        publish("tool call output", RuntimeListener::onToolCallOutputPolicy,
                listener -> listener.onToolCallOutput(event));
    }

    public void onAiMessage(AgentMessageEvent event) {
        publish("ai message", RuntimeListener::onAiMessagePolicy,
                listener -> listener.onAiMessage(event));
    }

    public void onExecutionError(ExecutionErrorEvent event) {
        publish("execution error", RuntimeListener::onExecutionErrorPolicy,
                listener -> listener.onExecutionError(event));
    }

    public void onExecutionComplete(ExecutionCompleteEvent event) {
        publish("execution completed", RuntimeListener::onExecutionCompletedPolicy,
                listener -> listener.onExecutionCompleted(event));
    }

    public void onExecutionCancelled(ExecutionCancelledEvent event) {
        publish("execution cancelled", RuntimeListener::onExecutionCancelledPolicy,
                listener -> listener.onExecutionCancelled(event));
    }

    public void onInvokeRetry(InvokeRetryEvent event) {
        publish("invoke retry", RuntimeListener::onInvokeRetryPolicy,
                listener -> listener.onInvokeRetry(event));
    }

    public void onApplicationEvent(Object event) {
        publish("application", RuntimeListener::onApplicationEventPolicy,
                listener -> listener.onApplicationEvent(event));
    }

    public void onPartialText(AgentPartialTextEvent event) {
        publish("partial text", RuntimeListener::onPartialTextPolicy,
                listener -> listener.onPartialText(event));
    }

    public void onPartialThinking(AgentPartialThinkingEvent event) {
        publish("partial thinking", RuntimeListener::onPartialThinkingPolicy,
                listener -> listener.onPartialThinking(event));
    }

    public void onCompleteText(AgentCompleteTextEvent event) {
        publish("complete text", RuntimeListener::onCompleteTextPolicy,
                listener -> listener.onCompleteText(event));
    }

    public void onContextUpdate(ContextUpdateEvent event) {
        publish("context update", RuntimeListener::onContextUpdatePolicy,
                listener -> listener.onContextUpdate(event));
    }

    public void onAuditRecorded(AgentAuditRecordedEvent event) {
        publish("audit recorded", RuntimeListener::onAuditRecordedPolicy,
                listener -> listener.onAuditRecorded(event));
    }

    public void onAuditFailed(AgentAuditFailedEvent event) {
        publish("audit failed", RuntimeListener::onAuditFailedPolicy,
                listener -> listener.onAuditFailed(event));
    }

    /**
     * The single dispatch for every event, in ascending {@link RuntimeListener#order()}.
     *
     * <p>The policy is read per listener and per event: a listener may be an invariant carrier for
     * one hook and a plain observer for the next. Containment is the default, because one broken
     * observer must not take the run down with it. A propagating listener stops the dispatch, so the
     * listeners after it never see the event — the same contract the loop's interceptor chain
     * offers.</p>
     */
    private void publish(String event, Function<RuntimeListener, RuntimeListener.Behavior> policy,
                         Consumer<RuntimeListener> delivery) {
        for (RuntimeListener listener : listeners) {
            try {
                delivery.accept(listener);
            } catch (RuntimeException e) {
                RuntimeListener.Behavior behavior = policy.apply(listener);
                if (behavior == null || behavior.catchErr()) {
                    log.error("Error occurred while publishing {} event, listenerId:{}, order:{}",
                            event, listener.id(), listener.order(), e);
                } else {
                    throw e;
                }
            }
        }
    }

}
