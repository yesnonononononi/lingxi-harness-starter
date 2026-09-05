package com.summit.core.tool;

import com.summit.core.conversation.api.ToolCallRequest;
import com.summit.core.runtime.Workspace;

import java.io.Serializable;
import java.util.List;

/**
 * A batch of tool calls for one agent turn.
 *
 * @param requests          the tool calls requested by the model
 * @param executionId       id of the current execution
 * @param sessionId         session id of the current execution
 * @param workspace         the workspace of the originating {@code AgentRequest};
 *                          tools MUST use this instance for all IO and command
 *                          execution — there is no global default
 * @param commandConfirmLevel approval level for command-line tools (from AgentRequest); may be null
 */
public record ToolExecuteCommand(List<ToolCallRequest> requests, String executionId, Serializable sessionId,
                                 Workspace workspace, CommandConfirmLevel commandConfirmLevel,LoopBoundary loopBoundary) {

    public ToolExecuteCommand(List<ToolCallRequest> requests, String executionId, Serializable sessionId, Workspace workspace) {
        this(requests, executionId, sessionId, workspace, null,null);
    }
}
