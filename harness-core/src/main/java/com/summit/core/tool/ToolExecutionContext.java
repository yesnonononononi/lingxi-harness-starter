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

    /**
     * Checks whether the given tool may be executed under the current loop boundary:
     * <ul>
     *   <li>read-only tools are always allowed (also during PLANNING);</li>
     *   <li>modifying tools are only allowed when the boundary permits execution
     *       ({@link LoopBoundary#allowExecute}, {@code null} = EXECUTE).</li>
     * </ul>
     */
    public boolean allowToolExecution(ToolDefinition<?> tool, LoopBoundary boundary) {
        if (tool == null) {
            return false;
        }
        if (tool.readOnly()) {
            return true;
        }
        return LoopBoundary.allowExecute(boundary);
    }
}
