package com.summit.harnesscore.tool;

import dev.langchain4j.agent.tool.ToolExecutionRequest;

import java.util.List;

public record ToolExecuteCommand(List<ToolExecutionRequest> requests, String executionId) {
}
