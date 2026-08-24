package com.summit.harnesscore.conversation.context;


import com.fasterxml.jackson.databind.ObjectMapper;
import com.summit.harnesscore.conversation.ConversationManager;
import com.summit.harnesscore.conversation.event.RuntimeEventPublisher;
import com.summit.harnesscore.model.ChatModelInvoker;
import com.summit.harnesscore.runtime.RuntimeExecutionPolicy;
import com.summit.harnesscore.runtime.Workspace;
import com.summit.harnesscore.tool.ToolExecutionManager;
import lombok.Builder;

@Builder
public record RuntimeContext(ChatModelInvoker modelInvoker,
                             Workspace workspace,
                             ConversationManager conversationManager,
                             RuntimeEventPublisher runtimeEventPublisher,
                             ToolExecutionManager toolExecutionManager,
                             RuntimeExecutionPolicy runtimeExecutionPolicy,
                             ObjectMapper objectMapper
) {


}
