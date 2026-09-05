package com.summit.core.runtime;



import com.summit.core.conversation.event.*;


public interface RuntimeListener {
    default void onExecutionStart(ExecutionStartEvent event) {
    }

    ;

    default void onToolCall(ToolCallStartEvent event) {
    }

    ;

    default void onToolCallOutput(ToolCallEndEvent event) {
    }

    ;

    default void onAiMessage(AgentMessageEvent event) {
    }

    ;

    default void onExecutionError(ExecutionErrorEvent event) {
    }

    ;

    default void onExecutionCompleted(ExecutionCompleteEvent event) {
    }

    ;

    default void onExecutionCancelled(ExecutionCancelledEvent event) {
    }

    default void onFileEdit(FileEditEvent event) {
    }

    default void onPartialText(AgentPartialTextEvent event) {
    }

    default void onPartialThinking(AgentPartialThinkingEvent event) {
    }

    default void onWaitCommandCheck(WaitCommandCheckEvent waitCommandCheckEvent){};

    default void onPlanDecision(PlanDecisionEvent event) {
    }
    ;

    default void onContextUpdate(ContextUpdateEvent event) {
    }
    ;
}

