package com.summit.core.tool;

import com.summit.core.conversation.event.RuntimeEventPublisher;
import lombok.Builder;

import java.util.List;

/** Startup-time tooling context. Deliberately holds NO workspace: the workspace is per-request, supplied by the {@code AgentRequest} and carried through {@link ToolExecuteCommand}. */
@Builder
public record ToolExecutionContext(RuntimeEventPublisher runtimeEventPublisher,
                                   ToolRegistry toolRegistry,
                                   Integer concurrentToolLimit
) {

    /** Checks whether the given tool may be executed under the request's tool whitelist. */
    public boolean allowToolExecution(ToolDefinition<?> tool, List<String> allowedTools) {
        return tool != null && tool.allowedFor(allowedTools);
    }
}
