package com.summit.runtime.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.summit.harnesscore.conversation.ConversationManager;
import com.summit.harnesscore.conversation.context.RuntimeContext;
import com.summit.harnesscore.conversation.event.RuntimeEventPublisher;
import com.summit.harnesscore.model.ChatModelInvoker;
import com.summit.harnesscore.model.StreamingModelInvoker;
import com.summit.harnesscore.runtime.ExecutionRuntime;
import com.summit.harnesscore.runtime.RuntimeExecutionPolicy;
import com.summit.harnesscore.runtime.RuntimeFactory;
import com.summit.harnesscore.runtime.Workspace;
import com.summit.harnesscore.tool.ToolExecutionManager;
import com.summit.runtime.ChatModelRuntimeProcessor;
import lombok.Builder;
import lombok.RequiredArgsConstructor;

@Builder
@RequiredArgsConstructor
public class DefaultRuntimeFactory implements RuntimeFactory {
    private final Workspace workspace;
    private final RuntimeEventPublisher runtimeEventPublisher;
    private final ToolExecutionManager toolExecutionManager;
    private final ConversationManager conversationManager;
    private final RuntimeExecutionPolicy runtimeExecutionPolicy;
    private final ObjectMapper objectMapper;

    @Override
    public ExecutionRuntime createChatModelRuntime(ChatModelInvoker chatModelInvoker) {
        return new ChatModelRuntimeProcessor(
                RuntimeContext.builder()
                        .workspace(workspace)
                        .modelInvoker(chatModelInvoker)
                        .runtimeEventPublisher(runtimeEventPublisher)
                        .toolExecutionManager(toolExecutionManager)
                        .conversationManager(conversationManager)
                        .runtimeExecutionPolicy(runtimeExecutionPolicy)
                        .objectMapper(objectMapper)
                        .build()
        );
    }

    @Override
    public ExecutionRuntime createStreamingModelRuntime(StreamingModelInvoker streamingModelInvoker) {
        return null;
    }
}
