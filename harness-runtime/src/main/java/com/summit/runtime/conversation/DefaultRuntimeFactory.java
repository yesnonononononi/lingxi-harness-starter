package com.summit.runtime.conversation;

import com.summit.core.compact.Tokenizer;
import com.summit.core.conversation.ConversationManager;
import com.summit.core.conversation.context.RuntimeContext;
import com.summit.core.conversation.event.RuntimeEventPublisher;
import com.summit.core.mcp.McpToolScope;
import com.summit.core.runtime.loop.ExecutionRepository;
import com.summit.core.model.ModelInvoker;
import com.summit.core.runtime.*;
import com.summit.core.runtime.loop.LoopInterceptor;
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
    private final ExecutionRepository executionRepository;
    private final LoopInterceptor loopInterceptor;
    /** Manual per-round truncation compaction (shouldSqueeze band). */
    private final DefaultManualCompacter manualCompacter;
    /** Model deep compaction (expectAdvanceSqueeze band). */
    private final DefaultModelCompacter modelCompacter;

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
                        .usage(usage)
                        .loopInterceptor(loopInterceptor)
                        .executionRepository(executionRepository)
                        .mcpToolScope(mcpToolScope)
                        .maxConsecutiveCompactions(agentConfig.maxConsecutiveCompactions())
                        .runtimeBoundaryChecker(
                                new BoundaryChecker(agentConfig, tokenizer, conversationManager,
                                        manualCompacter, modelCompacter))
                        .build()
        );
    }
}
