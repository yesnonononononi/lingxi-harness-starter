package com.summit.core.conversation.event;

import com.summit.core.tool.ToolCallStatus;

/** Common contract for every event in a tool call's lifecycle. */
public interface ToolCallEvent extends AgentEvent {
    ToolCallStatus resultStatus();
}
