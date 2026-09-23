package com.summit.runtime.conversation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.summit.core.compact.Tokenizer;
import com.summit.core.conversation.ConversationManager;
import com.summit.core.conversation.context.RuntimeContext;
import com.summit.core.conversation.event.RuntimeEventPublisher;
import com.summit.core.runtime.loop.AgentLoopHook;
import com.summit.core.runtime.loop.ActiveExecutionRegistry;
import com.summit.core.runtime.loop.ExecutionRepository;
import com.summit.core.model.ModelInvoker;
import com.summit.core.runtime.*;
import com.summit.core.runtime.loop.lifstyle.RuntimeLifeStyleManager;
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
    private final ObjectMapper objectMapper;
    private final Tokenizer tokenizer;
    private final AgentConfig agentConfig;
    private final RuntimeLifeStyleManager runtimeLifeStyleManager;
    private final AgentLoopHook agentLoopHook;
    private final ActiveExecutionRegistry activeExecutionRegistry;
    private final ExecutionRepository executionRepository;
    /** Manual per-round truncation compaction (shouldSqueeze band). */
    private final DefaultManualCompacter manualCompacter;
    /** Model deep compaction (expectAdvanceSqueeze band). */
    private final DefaultModelCompacter modelCompacter;

    @Override
    public ExecutionRuntime createChatModelRuntime(String executionId, ModelInvoker chatModelInvoker, Workspace workspace) {
        return createModelRuntime(executionId, chatModelInvoker, workspace);
    }

    @Override
    public ExecutionRuntime createStreamingModelRuntime(String executionId, ModelInvoker streamingModelInvoker, Workspace workspace) {
        return createModelRuntime(executionId, streamingModelInvoker, workspace);
    }

    private ExecutionRuntime createModelRuntime(String executionId, ModelInvoker modelInvoker, Workspace workspace) {
        return new RuntimeProcessorTemplate(
                RuntimeContext.builder()
                        .workspace(workspace)
                        .invoker(modelInvoker)
                        .runtimeEventPublisher(runtimeEventPublisher)
                        .toolExecutionManager(toolExecutionManager)
                        .conversationManager(conversationManager)
                        .objectMapper(objectMapper)
                        .maxIterations(agentConfig.maxIterations())
                        .runtimeLifeStyleManager(runtimeLifeStyleManager)
                        .maxTokens(agentConfig.maxTokens())
                        .tokenizer(tokenizer)
                        .agentLoopHook(agentLoopHook)
                        .activeExecutionRegistry(activeExecutionRegistry)
                        .executionRepository(executionRepository)
                        .runtimeBoundaryChecker(
                                new BoundaryChecker(agentConfig, tokenizer, conversationManager,
                                        manualCompacter, modelCompacter))
                        .build()
        );
    }
}
