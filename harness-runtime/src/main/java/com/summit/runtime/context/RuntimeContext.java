package com.summit.runtime.context;


import com.summit.core.compact.Tokenizer;
import com.summit.core.conversation.ConversationManager;
import com.summit.core.conversation.api.ResponseIdGenerator;
import com.summit.core.conversation.event.RuntimeEventPublisher;
import com.summit.core.runtime.loop.*;
import com.summit.core.mcp.McpToolScope;
import com.summit.core.model.ModelInvoker;
import com.summit.core.runtime.loop.lifestyle.RuntimeLifeStyleManager;
import com.summit.core.runtime.workspace.Workspace;
import com.summit.core.tool.ToolExecutionManager;
import com.summit.runtime.conversation.SnowflakeResponseIdGenerator;
import lombok.Getter;
import lombok.experimental.SuperBuilder;

import java.util.List;

/**
 * Everything one loop run needs, gathered by the runtime and handed to the loop.
 *
 * <p>An assembly object, not a domain type: it holds no execution state and no invariant, and it
 * lives in the runtime because only the runtime decides how a run is wired. The callback surface
 * applications program against is {@link com.summit.core.runtime.loop.LoopContext}.</p>
 */
@Getter
@SuperBuilder
public class RuntimeContext {
    private final RuntimeLifeStyleManager runtimeLifeStyleManager;
    private final List<ExecutionFailureObserver> failureObservers;
    private final RuntimeBoundaryChecker runtimeBoundaryChecker;
    private final ExecutionRepository executionRepository;
    private final ExecutionControl executionControl;
    private final ModelInvoker invoker;
    private final LoopInterceptorProcessor loopInterceptorProcessor;
    private final Workspace workspace;
    private final ConversationManager conversationManager;
    private final RuntimeEventPublisher runtimeEventPublisher;
    private final ResponseIdGenerator responseIdGenerator;
    private final ToolExecutionManager toolExecutionManager;
    private final ContextUsageReporter usage;
    private final Tokenizer tokenizer;
    /**
     * MCP tools declared by this request. Request-scoped by construction and closed by the runtime
     * when the execution reaches a terminal state.
     */
    private final McpToolScope mcpToolScope;
    /**
     * Consecutive compaction rounds this run tolerates before the loop gives up, or {@code null} to
     * use the runtime's default.
     *
     * <p>Carried on the context rather than read from a constant inside the loop, so the budget is a
     * configuration decision like every other one. Left nullable because this context is also built
     * directly outside Spring, where the caller may have no opinion.</p>
     */
    private final Integer maxConsecutiveCompactions;




    /** The MCP tools of this request, never {@code null}. */
    public McpToolScope getMcpToolScope() {
        return mcpToolScope == null ? McpToolScope.EMPTY : mcpToolScope;
    }

    public List<ExecutionFailureObserver> getFailureObservers() {
        return failureObservers == null ? List.of() : failureObservers;
    }

    /** Direct builder users share one generator so separate runtimes do not reuse worker sequences. */
    public ResponseIdGenerator getResponseIdGenerator() {
        return responseIdGenerator == null ? SnowflakeResponseIdGenerator.DEFAULT : responseIdGenerator;
    }

}
