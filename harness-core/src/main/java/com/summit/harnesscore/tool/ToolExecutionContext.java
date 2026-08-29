package com.summit.harnesscore.tool;

import com.summit.harnesscore.conversation.event.RuntimeEventPublisher;
import lombok.Builder;

/**
 * Startup-time tooling context. Deliberately holds NO workspace: the workspace
 * is per-request, supplied by the {@code AgentRequest} and carried through
 * {@link ToolExecuteCommand}.
 */
@Builder
public record ToolExecutionContext(RuntimeEventPublisher runtimeEventPublisher, ToolRegistry toolRegistry) {
}
