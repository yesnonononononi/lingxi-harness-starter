package com.summit.core.conversation.event;


import com.summit.core.conversation.AbstractEventPublisher;
import com.summit.core.runtime.RuntimeListener;

import java.util.List;

/**
 * The run's record, published to {@link RuntimeListener}s in ascending
 * {@link RuntimeListener#order()}.
 *
 * <p>Streaming events ({@link AgentEventPublisher}) and context events ({@link ContextEventPublisher})
 * are published separately on purpose; this publisher carries only the lifecycle, tool, retry and
 * audit facts of a run.</p>
 */
public class RuntimeEventPublisher extends AbstractEventPublisher<RuntimeListener> {
    public RuntimeEventPublisher(List<RuntimeListener> listeners) {
        super(listeners);
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

    public void onToolCallPreparing(ToolCallPrepareEvent event) {
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

    public void onAuditRecorded(AgentAuditRecordedEvent event) {
        publish("audit recorded", RuntimeListener::onAuditRecordedPolicy,
                listener -> listener.onAuditRecorded(event));
    }

    public void onAuditFailed(AgentAuditFailedEvent event) {
        publish("audit failed", RuntimeListener::onAuditFailedPolicy,
                listener -> listener.onAuditFailed(event));
    }
}
