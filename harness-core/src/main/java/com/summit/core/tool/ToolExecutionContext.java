package com.summit.core.tool;

import com.summit.core.conversation.event.RuntimeEventPublisher;
import lombok.Builder;

/**
 * Startup-time tooling context. Deliberately holds NO workspace: the workspace
 * is per-request, supplied by the {@code AgentRequest} and carried through
 * {@link ToolExecuteCommand}.
 */
@Builder
public record ToolExecutionContext(RuntimeEventPublisher runtimeEventPublisher, ToolRegistry toolRegistry) {
}
