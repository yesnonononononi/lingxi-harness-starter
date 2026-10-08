package com.summit.runtime.conversation;

import com.summit.core.agent.Execution;
import com.summit.core.compact.Tokenizer;
import com.summit.core.conversation.ConversationManager;
import com.summit.runtime.context.RuntimeContext;
import com.summit.core.conversation.event.RuntimeEventPublisher;
import com.summit.core.mcp.McpToolScope;
import com.summit.core.runtime.loop.*;
import com.summit.core.model.ModelInvoker;
import com.summit.core.runtime.*;
import com.summit.core.runtime.loop.lifestyle.RuntimeLifeStyleManager;
import com.summit.core.runtime.workspace.Workspace;
import com.summit.core.tool.ToolExecutionManager;
import com.summit.runtime.loop.BoundaryChecker;
import com.summit.runtime.loop.RuntimeProcessorTemplate;
import com.summit.runtime.agent.AgentConfig;
import com.summit.runtime.compact.DefaultManualCompacter;
import com.summit.runtime.compact.DefaultModelCompacter;
import lombok.Builder;
import lombok.RequiredArgsConstructor;

import java.util.List;

@Builder
@RequiredArgsConstructor
public class DefaultRuntimeFactory implements RuntimeFactory {
    private final RuntimeEventPublisher runtimeEventPublisher;
    private final ToolExecutionManager toolExecutionManager;
    private final ConversationManager conversationManager;
    private final Tokenizer tokenizer;
    private final com.summit.core.runtime.loop.ContextUsageReporter usage;
    private final AgentConfig agentConfig;
    private final RuntimeLifeStyleManager runtimeLifeStyleManager;
    private final List<ExecutionFailureObserver> failureObservers;
    private final ExecutionRepository executionRepository;
    private final ExecutionControl executionControl;
    private final LoopInterceptorProcessor loopInterceptorProcessor;
    /** Manual per-round truncation compaction (shouldSqueeze band). */
    private final DefaultManualCompacter manualCompacter;
    /** Model deep compaction (expectAdvanceSqueeze band). */
    private final DefaultModelCompacter modelCompacter;
    /**
     * The per-execution boundary checker (budget limits, compaction bands, token exhaustion).
     *
     * <p>Injected rather than constructed here so the decision policy is replaceable: the checker is
     * stateless — every bit of per-run state lives on the {@link Execution} passed to it — so one
     * shared instance serves all executions. Left nullable and defaulted below for direct builder use
     * outside Spring.</p>
     */
    private final RuntimeBoundaryChecker boundaryChecker;

    @Override
    public ExecutionRuntime createRuntime(ModelInvoker modelInvoker, Workspace workspace, McpToolScope mcpToolScope) {
        return new RuntimeProcessorTemplate(
                RuntimeContext.builder()
                        .workspace(workspace)
                        .invoker(modelInvoker)
                        .runtimeEventPublisher(runtimeEventPublisher)
                        .toolExecutionManager(toolExecutionManager)
                        .conversationManager(conversationManager)
                        .runtimeLifeStyleManager(runtimeLifeStyleManager)
                        .failureObservers(failureObservers)
                        .usage(usage)
                        .tokenizer(tokenizer)
                        .loopInterceptorProcessor(loopInterceptorProcessor)
                        .executionRepository(executionRepository)
                        .executionControl(executionControl)
                        .mcpToolScope(mcpToolScope)
                        .maxConsecutiveCompactions(agentConfig.maxConsecutiveCompactions())
                        .runtimeBoundaryChecker(boundaryCheckerOrDefault())
                        .build()
        );
    }

    /** The configured checker, or a freshly built default one when the caller wired none. */
    private RuntimeBoundaryChecker boundaryCheckerOrDefault() {
        if (boundaryChecker != null) {
            return boundaryChecker;
        }
        return new BoundaryChecker(agentConfig, tokenizer, conversationManager,
                manualCompacter, modelCompacter);
    }
}
