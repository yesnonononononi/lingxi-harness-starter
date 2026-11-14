package com.summit.core.runtime;



import com.summit.core.conversation.event.*;

import java.util.UUID;


public interface RuntimeListener {
    /**
     * How the publisher treats a listener's failure for one event.
     *
     * <p>Containment is the default: one broken observer must not take the run down with it. A
     * listener that carries part of the run's own invariant answers
     * {@link Behavior#PROPAGATE} for that hook, and its failure then stops the dispatch — the
     * listeners after it never see the event.</p>
     */
    record Behavior(boolean catchErr) {
        /** Log the failure and continue with the remaining listeners. */
        public static final Behavior CONTAIN = new Behavior(true);
        /** Propagate the failure; the remaining listeners do not receive the event. */
        public static final Behavior PROPAGATE = new Behavior(false);
    }

    /**
     * Dispatch position among listeners of one publisher. Lower numbers run earlier, matching
     * {@link com.summit.core.runtime.loop.LoopInterceptor#order()}.
     *
     * <p>Deliberately abstract: a listener that never states where it belongs in the chain gets its
     * position from whoever registered it, which is exactly the ambiguity this ordering exists to
     * remove.</p>
     */
    int order();

    default String id(){
        return UUID.randomUUID().toString();
    }

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

    /** Failure policy of {@link #onAiMessage}. */
    default Behavior onAiMessagePolicy(){ return Behavior.CONTAIN; }

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

    /** Failure policy of {@link #onPartialText}. */
    default Behavior onPartialTextPolicy(){ return Behavior.CONTAIN; }

    /** Failure policy of {@link #onPartialThinking}. */
    default Behavior onPartialThinkingPolicy(){ return Behavior.CONTAIN; }

    /** Failure policy of {@link #onCompleteText}. */
    default Behavior onCompleteTextPolicy(){ return Behavior.CONTAIN; }

    /** Failure policy of {@link #onContextUpdate}. */
    default Behavior onContextUpdatePolicy(){ return Behavior.CONTAIN; }

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

    default void onAiMessage(AgentMessageEvent event) {
    }

    default void onExecutionError(ExecutionErrorEvent event) {
    }

    default void onExecutionCompleted(ExecutionCompleteEvent event) {
    }

    default void onExecutionCancelled(ExecutionCancelledEvent event) {
    }

    default void onApplicationEvent(Object event) {
    }

    default void onPartialText(AgentPartialTextEvent event) {
    }

    default void onPartialThinking(AgentPartialThinkingEvent event) {
    }

    default void onContextUpdate(ContextUpdateEvent event) {
    }

    default void onCompleteText(AgentCompleteTextEvent event){};

    default  void onInvokeRetry(InvokeRetryEvent event){};

    default void onAuditRecorded(AgentAuditRecordedEvent event) {
    }

    default void onAuditFailed(AgentAuditFailedEvent event) {
    }

    default void onToolCallPrepare(ToolCallPrepareEvent event){};
}
