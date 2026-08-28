package com.summit.harnesscore.tool;

import com.summit.harnesscore.conversation.event.RuntimeEventPublisher;
import com.summit.harnesscore.runtime.Workspace;
import lombok.Builder;

@Builder
public record ToolExecutionContext(Workspace workspace, RuntimeEventPublisher runtimeEventPublisher,ToolRegistry toolRegistry) {
}
