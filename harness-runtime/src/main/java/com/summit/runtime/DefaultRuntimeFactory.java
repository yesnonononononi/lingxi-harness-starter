package com.summit.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.summit.harnesscore.conversation.ConversationManager;
import com.summit.harnesscore.conversation.context.RuntimeContext;
import com.summit.harnesscore.conversation.event.RuntimeEventPublisher;
import com.summit.harnesscore.model.ModelInvoker;
import com.summit.harnesscore.runtime.ExecutionRuntime;
import com.summit.harnesscore.runtime.RuntimeExecutionPolicy;
import com.summit.harnesscore.runtime.RuntimeFactory;
import com.summit.harnesscore.runtime.Workspace;
import com.summit.harnesscore.tool.ToolExecutionManager;
import lombok.Builder;
import lombok.RequiredArgsConstructor;

import java.io.Serializable;

@Builder
@RequiredArgsConstructor
public class DefaultRuntimeFactory implements RuntimeFactory {
    private final RuntimeEventPublisher runtimeEventPublisher;
    private final ToolExecutionManager toolExecutionManager;
    private final ConversationManager conversationManager;
    private final RuntimeExecutionPolicy runtimeExecutionPolicy;
    private final ObjectMapper objectMapper;
    private final Integer maxIterations;

    @Override
    public ExecutionRuntime createChatModelRuntime(Serializable sessionId, ModelInvoker chatModelInvoker, Workspace workspace) {
        return new RuntimeProcessorTemplate(
                RuntimeContext.builder()
                        .workspace(workspace)
                        .invoker(chatModelInvoker)
                        .runtimeEventPublisher(runtimeEventPublisher)
                        .toolExecutionManager(toolExecutionManager)
                        .conversationManager(conversationManager)
                        .runtimeExecutionPolicy(runtimeExecutionPolicy)
                        .objectMapper(objectMapper)
                        .maxIterations(maxIterations)
                        .build()
        ) {
        };
    }

    @Override
    public ExecutionRuntime createStreamingModelRuntime(Serializable sessionId, ModelInvoker streamingModelInvoker, Workspace workspace) {
        return new RuntimeProcessorTemplate(
                RuntimeContext.builder()
                        .invoker(streamingModelInvoker)
                        .workspace(workspace)
                        .runtimeEventPublisher(runtimeEventPublisher)
                        .toolExecutionManager(toolExecutionManager)
                        .conversationManager(conversationManager)
                        .runtimeExecutionPolicy(runtimeExecutionPolicy)
                        .objectMapper(objectMapper)
                        .maxIterations(maxIterations)
                        .build()
        );
    }
}
