package com.summit.core.tool;

import com.summit.core.agent.ToolSpec;
import com.summit.core.conversation.event.RuntimeEventPublisher;
import lombok.Builder;

/** Startup-time tooling context. Deliberately holds NO workspace: the workspace is per-request, supplied by the {@code AgentRequest} and carried through {@link ToolExecuteCommand}. */
@Builder
public record ToolExecutionContext(RuntimeEventPublisher runtimeEventPublisher,
                                   ToolRegistry toolRegistry,
                                   Integer concurrentToolLimit
) {

    /**
     * Whether the given tool may be executed under the request's {@link ToolSpec} — the whitelist and
     * the per-round blacklist together. Model visibility alone is {@link ToolDefinition#visibleToModel}.
     */
    public boolean allowToolExecution(ToolDefinition<?> tool, ToolSpec toolSpec) {
        return tool != null && tool.executableUnder(toolSpec);
    }
}
