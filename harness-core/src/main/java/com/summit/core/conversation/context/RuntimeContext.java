package com.summit.core.conversation.context;


import com.fasterxml.jackson.databind.ObjectMapper;
import com.summit.core.compact.Tokenizer;
import com.summit.core.conversation.ConversationManager;
import com.summit.core.conversation.event.RuntimeEventPublisher;
import com.summit.core.model.ModelInvoker;
import com.summit.core.runtime.*;
import com.summit.core.runtime.lifstyle.LifeStyleCommandRegistry;
import com.summit.core.runtime.lifstyle.LifeStyleCommandStore;
import com.summit.core.runtime.lifstyle.RuntimeLifeStyleManager;
import com.summit.core.runtime.workspace.Workspace;
import com.summit.core.tool.ToolExecutionManager;
import lombok.Getter;
import lombok.experimental.SuperBuilder;

@Getter
@SuperBuilder
public class RuntimeContext
 {
    private final RuntimeLifeStyleManager runtimeLifeStyleManager;
    private final CheckPointer checkPointer;

    private final ModelInvoker invoker;
    private final Workspace workspace;
    private final ConversationManager conversationManager;
    private final RuntimeEventPublisher runtimeEventPublisher;
    private final ToolExecutionManager toolExecutionManager;
    private final ObjectMapper objectMapper;
    private final Integer maxIterations;
    private final Integer maxTokens;
    /** Token estimator of the session; used to report the live context usage to the front-end. */
    private final Tokenizer tokenizer;
    /** Per-execution command store created by the runtime factory (optional). */
    private final LifeStyleCommandStore lifeStyleCommandStore;
    /** Registry the execution registered its store in, released on exit (optional). */
    private final LifeStyleCommandRegistry lifeStyleCommandRegistry;
    /** Optional application hook. The runtime itself has no plan or approval semantics. */
    private final AgentLoopHook agentLoopHook;
    /** Generic suspension SPI shared by application workflows. */
    private final LoopSuspender loopSuspender;
    private static final int DEFAULT_MAX_ITERATIONS = 10;
    private static final int DEFAULT_MAX_TOKENS = 100000;


    public int getMaxIterations() {
        return maxIterations != null ? maxIterations : DEFAULT_MAX_ITERATIONS;
    }
    public int getMaxTokens() {
        return maxTokens != null ? maxTokens : DEFAULT_MAX_TOKENS;
    }

}
