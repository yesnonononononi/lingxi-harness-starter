package com.summit.harnesscore.tool;

import com.summit.harnesscore.conversation.api.ToolCallRequest;

import java.io.Serializable;
import java.util.List;

public record ToolExecuteCommand(List<ToolCallRequest> requests, String executionId, Serializable sessionId) {
}
