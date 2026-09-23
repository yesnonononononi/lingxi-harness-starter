package com.summit.core.conversation.context;


import com.fasterxml.jackson.databind.ObjectMapper;
import com.summit.core.compact.Tokenizer;
import com.summit.core.conversation.ConversationManager;
import com.summit.core.conversation.event.RuntimeEventPublisher;
import com.summit.core.runtime.loop.AgentLoopHook;
import com.summit.core.runtime.loop.ContextUsageReporter;
import com.summit.core.runtime.loop.ActiveExecutionRegistry;
import com.summit.core.runtime.loop.ExecutionRepository;
import com.summit.core.runtime.loop.RuntimeBoundaryChecker;
import com.summit.core.model.ModelInvoker;
import com.summit.core.runtime.loop.lifstyle.RuntimeLifeStyleManager;
import com.summit.core.runtime.workspace.Workspace;
import com.summit.core.tool.ToolExecutionManager;
import lombok.Getter;
import lombok.experimental.SuperBuilder;

@Getter
@SuperBuilder
public class RuntimeContext {
    private final RuntimeLifeStyleManager runtimeLifeStyleManager;
    private final RuntimeBoundaryChecker runtimeBoundaryChecker;
    private final ActiveExecutionRegistry activeExecutionRegistry;
    private final ExecutionRepository executionRepository;
    private final ModelInvoker invoker;
    private final Workspace workspace;
    private final ConversationManager conversationManager;
    private final RuntimeEventPublisher runtimeEventPublisher;
    private final ToolExecutionManager toolExecutionManager;
    private final ObjectMapper objectMapper;
    private final Integer maxIterations;
    private final Integer maxTokens;
    private final ContextUsageReporter usage;
    /**
     * Token estimator of the session; used to report the live context usage to the front-end.
     */
    private final Tokenizer tokenizer;
    /**
     * Optional application hook. The runtime itself carries no product semantics.
     */
    private final AgentLoopHook agentLoopHook;
    private static final int DEFAULT_MAX_ITERATIONS = 10;
    private static final int DEFAULT_MAX_TOKENS = 100000;


    public int getMaxIterations() {
        return maxIterations != null ? maxIterations : DEFAULT_MAX_ITERATIONS;
    }

    public int getMaxTokens() {
        return maxTokens != null ? maxTokens : DEFAULT_MAX_TOKENS;
    }

}
