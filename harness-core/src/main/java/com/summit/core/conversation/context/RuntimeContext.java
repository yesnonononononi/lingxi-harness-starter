package com.summit.core.conversation.context;


import com.fasterxml.jackson.databind.ObjectMapper;
import com.summit.core.conversation.ConversationManager;
import com.summit.core.conversation.event.RuntimeEventPublisher;
import com.summit.core.model.ModelInvoker;
import com.summit.core.runtime.RuntimeExecutionPolicy;
import com.summit.core.runtime.Workspace;
import com.summit.core.tool.ToolExecutionManager;
import lombok.Getter;
import lombok.experimental.SuperBuilder;

@Getter
@SuperBuilder
public class RuntimeContext
 {
    private final ModelInvoker invoker;
    private final Workspace workspace;
    private final ConversationManager conversationManager;
    private final RuntimeEventPublisher runtimeEventPublisher;
    private final ToolExecutionManager toolExecutionManager;
    private final RuntimeExecutionPolicy runtimeExecutionPolicy;
    private final ObjectMapper objectMapper;
    private final Integer maxIterations;
    private static final int DEFAULT_MAX_ITERATIONS = 10;
    public int getMaxIterations() {
        return maxIterations != null ? maxIterations : DEFAULT_MAX_ITERATIONS;
    }

}
