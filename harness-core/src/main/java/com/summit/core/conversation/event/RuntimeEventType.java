package com.summit.core.conversation.event;

public enum RuntimeEventType {
    EXECUTION_STARTED,

    MODEL_STARTED,
    MODEL_DELTA,
    MODEL_COMPLETED,

    TOOL_STARTED,
    TOOL_COMPLETED,

    EXECUTION_COMPLETED,
    EXECUTION_FAILED
}
