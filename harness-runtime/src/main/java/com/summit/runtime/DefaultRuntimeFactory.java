package com.summit.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.summit.core.compact.Tokenizer;
import com.summit.core.conversation.ConversationManager;
import com.summit.core.conversation.context.RuntimeContext;
import com.summit.core.conversation.event.RuntimeEventPublisher;
import com.summit.core.model.ModelInvoker;
import com.summit.core.runtime.*;
import com.summit.core.tool.PlanApprovalRegistry;
import com.summit.core.tool.ToolExecutionManager;
import com.summit.runtime.agent.AgentConfig;
import com.summit.runtime.compact.DefaultManualCompacter;
import com.summit.runtime.compact.DefaultModelCompacter;
import lombok.Builder;
import lombok.RequiredArgsConstructor;

import java.io.Serializable;

@Builder
@RequiredArgsConstructor
public class DefaultRuntimeFactory implements RuntimeFactory {
    private final RuntimeEventPublisher runtimeEventPublisher;
    private final ToolExecutionManager toolExecutionManager;
    private final ConversationManager conversationManager;
    private final ObjectMapper objectMapper;
    private final Tokenizer tokenizer;
    private final AgentConfig agentConfig;
    private final LifeStyleHandler lifeStyleHandler;
    private final LifeStyleCommandRegistry lifeStyleCommandRegistry;
    private final PlanApprovalRegistry planApprovalRegistry;
    /** Manual per-round truncation compaction (shouldSqueeze band). */
    private final DefaultManualCompacter manualCompacter;
    /** Model deep compaction (expectAdvanceSqueeze band). */
    private final DefaultModelCompacter modelCompacter;

    @Override
    public ExecutionRuntime createChatModelRuntime(Serializable sessionId, ModelInvoker chatModelInvoker, Workspace workspace) {
        return createModelRuntime(sessionId, chatModelInvoker, workspace);
    }

    @Override
    public ExecutionRuntime createStreamingModelRuntime(Serializable sessionId, ModelInvoker streamingModelInvoker, Workspace workspace) {
        return createModelRuntime(sessionId, streamingModelInvoker, workspace);
    }

    /**
     * Each execution gets its own command store, freshly created and bound to the
     * session. Pause/resume/stop issued for one execution therefore never leak
     * into another execution (or a later execution of the same session). The
     * store is unregistered and dropped by {@link RuntimeProcessorTemplate}
     * when the execution finishes.
     */
    private ExecutionRuntime createModelRuntime(Serializable sessionId, ModelInvoker modelInvoker, Workspace workspace) {
        LifeStyleCommandStore commandStore = lifeStyleCommandRegistry.register(sessionId);
        return new RuntimeProcessorTemplate(
                RuntimeContext.builder()
                        .workspace(workspace)
                        .invoker(modelInvoker)
                        .runtimeEventPublisher(runtimeEventPublisher)
                        .toolExecutionManager(toolExecutionManager)
                        .conversationManager(conversationManager)
                        .objectMapper(objectMapper)
                        .maxIterations(agentConfig.maxIterations())
                        .lifeStyleCommandStore(commandStore)
                        .lifeStyleCommandRegistry(lifeStyleCommandRegistry)
                        .planApprovalRegistry(planApprovalRegistry)
                        .checkPointer(
                                new RuntimeCheckPointer(lifeStyleHandler, agentConfig, tokenizer, conversationManager,
                                        commandStore, manualCompacter, modelCompacter
                                ))
                        .build()
        );
    }
}
