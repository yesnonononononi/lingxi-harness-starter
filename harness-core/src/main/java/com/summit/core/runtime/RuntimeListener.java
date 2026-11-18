package com.summit.core.runtime;


import com.summit.core.conversation.EventListener;
import com.summit.core.conversation.event.*;

/**
 * The audit surface of a run: lifecycle, tools, retries and token audits.
 *
 * <p>The streaming callbacks live on {@link AgentStreamListener}: a run's record is a handful of
 * facts, a response's rendering is one call per token chunk, and one interface cannot serve both
 * without waking every auditor at token frequency. Context notifications live on
 * {@link com.summit.core.conversation.event.ContextListener} for the same reason.</p>
 */
public interface RuntimeListener extends EventListener {


    /** Failure policy of {@link #onExecutionStart}. */
    default Behavior onExecutionStartPolicy(){ return Behavior.CONTAIN; }

    /** Failure policy of {@link #onExecutionResumed}. */
    default Behavior onExecutionResumedPolicy(){ return Behavior.CONTAIN; }

    /** Failure policy of {@link #onExecutionSuspended}. */
    default Behavior onExecutionSuspendedPolicy(){ return Behavior.CONTAIN; }

    /** Failure policy of {@link #onToolCallPrepare}. */
    default Behavior onToolCallPreparePolicy(){ return Behavior.CONTAIN; }

    /** Failure policy of {@link #onToolCall}. */
    default Behavior onToolCallPolicy(){ return Behavior.CONTAIN; }

    /** Failure policy of {@link #onToolCallOutput}. */
    default Behavior onToolCallOutputPolicy(){ return Behavior.CONTAIN; }

    /** Failure policy of {@link #onExecutionError}. */
    default Behavior onExecutionErrorPolicy(){ return Behavior.CONTAIN; }

    /** Failure policy of {@link #onExecutionCompleted}. */
    default Behavior onExecutionCompletedPolicy(){ return Behavior.CONTAIN; }

    /** Failure policy of {@link #onExecutionCancelled}. */
    default Behavior onExecutionCancelledPolicy(){ return Behavior.CONTAIN; }

    /** Failure policy of {@link #onInvokeRetry}. */
    default Behavior onInvokeRetryPolicy(){ return Behavior.CONTAIN; }

    /** Failure policy of {@link #onApplicationEvent}. */
    default Behavior onApplicationEventPolicy(){ return Behavior.CONTAIN; }

    /** Failure policy of {@link #onAuditRecorded}. */
    default Behavior onAuditRecordedPolicy(){ return Behavior.CONTAIN; }

    /** Failure policy of {@link #onAuditFailed}. */
    default Behavior onAuditFailedPolicy(){ return Behavior.CONTAIN; }

    default void onExecutionStart(ExecutionStartEvent event) {
    }

    default void onExecutionResumed(ExecutionResumedEvent event){}

    default void onExecutionSuspended(ExecutionSuspendedEvent event){}

    default void onToolCall(ToolCallStartEvent event) {
    }

    default void onToolCallOutput(ToolCallEndEvent event) {
    }

    default void onExecutionError(ExecutionErrorEvent event) {
    }

    default void onExecutionCompleted(ExecutionCompleteEvent event) {
    }

    default void onExecutionCancelled(ExecutionCancelledEvent event) {
    }

    default void onApplicationEvent(Object event) {
    }

    default  void onInvokeRetry(InvokeRetryEvent event){};

    default void onAuditRecorded(AgentAuditRecordedEvent event) {
    }

    default void onAuditFailed(AgentAuditFailedEvent event) {
    }

    default void onToolCallPrepare(ToolCallPrepareEvent event){};
}
