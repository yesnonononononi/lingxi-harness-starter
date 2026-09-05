package com.summit.core.conversation.context;


import com.fasterxml.jackson.databind.ObjectMapper;
import com.summit.core.conversation.ConversationManager;
import com.summit.core.conversation.event.RuntimeEventPublisher;
import com.summit.core.model.ModelInvoker;
import com.summit.core.runtime.CheckPointer;
import com.summit.core.runtime.LifeStyleCommandRegistry;
import com.summit.core.runtime.LifeStyleCommandStore;
import com.summit.core.runtime.Workspace;
import com.summit.core.tool.PlanApprovalRegistry;
import com.summit.core.tool.ToolExecutionManager;
import lombok.Getter;
import lombok.experimental.SuperBuilder;

@Getter
@SuperBuilder
public class RuntimeContext
 {
    private final CheckPointer checkPointer;
    private final ModelInvoker invoker;
    private final Workspace workspace;
    private final ConversationManager conversationManager;
    private final RuntimeEventPublisher runtimeEventPublisher;
    private final ToolExecutionManager toolExecutionManager;
    private final ObjectMapper objectMapper;
    private final Integer maxIterations;
    /** Per-execution command store created by the runtime factory (optional). */
    private final LifeStyleCommandStore lifeStyleCommandStore;
    /** Registry the execution registered its store in, released on exit (optional). */
    private final LifeStyleCommandRegistry lifeStyleCommandRegistry;
    /**
     * Registry of plans awaiting human approval, used by the plan-level approval
     * gate (PLANING -&gt; user APPROVE/REJECT -&gt; EXECUTE). Optional: when absent,
     * a captured plan is auto-approved (legacy behaviour).
     */
    private final PlanApprovalRegistry planApprovalRegistry;
    private static final int DEFAULT_MAX_ITERATIONS = 10;
    public int getMaxIterations() {
        return maxIterations != null ? maxIterations : DEFAULT_MAX_ITERATIONS;
    }

}
