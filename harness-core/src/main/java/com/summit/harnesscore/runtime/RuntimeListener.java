package com.summit.harnesscore.runtime;



import com.summit.harnesscore.conversation.event.*;


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

}

