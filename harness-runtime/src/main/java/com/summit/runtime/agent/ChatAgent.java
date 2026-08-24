package com.summit.runtime.agent;

import com.summit.harnesscore.agent.Agent;
import com.summit.harnesscore.agent.AgentRequest;
import com.summit.harnesscore.agent.Execution;
import com.summit.harnesscore.runtime.ExecutionRuntime;
import com.summit.harnesscore.runtime.RuntimeFactory;
import com.summit.harnesscore.runtime.Workspace;
import com.summit.harnesscore.tool.ToolRegistry;
import com.summit.runtime.model.DefaultChatModelInvoker;
import dev.langchain4j.model.chat.ChatModel;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class ChatAgent implements Agent {
    private final ChatModel chatModel;
    private final RuntimeFactory defaultRuntimeFactory;

    @Override
    public String id() {
        return "chat-agent";
    }

    @Override
    public Execution execute(AgentRequest agentRequest) {
        Execution execution = ExecutionCreator.create(agentRequest, this);
        ExecutionRuntime executionRuntime =defaultRuntimeFactory.createChatModelRuntime(
                new DefaultChatModelInvoker(chatModel)
        ) ;
        return executionRuntime.execute(execution);
    }
}
