package com.summit.core.tool;

import com.summit.core.runtime.Workspace;

import lombok.Builder;
import lombok.Data;

import java.io.Serializable;

@Builder
@Data
public class ToolExecution {
    private String id;
    private ToolDefinition<? extends ToolExecutor> toolDefinition;
    private String args;
    private final Serializable sessionId;
    /** Id of the agent request (turn) this tool call belongs to. */
    private String turnId;
    private Workspace workspace;
}
