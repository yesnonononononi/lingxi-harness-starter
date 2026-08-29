package com.summit.harnesscore.tool;

import com.summit.harnesscore.runtime.Workspace;

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
    private Workspace workspace;
}
