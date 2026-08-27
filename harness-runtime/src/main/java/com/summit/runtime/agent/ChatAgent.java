package com.summit.runtime.agent;

import com.summit.harnesscore.agent.Agent;
import com.summit.harnesscore.agent.AgentRequest;
import com.summit.harnesscore.agent.Execution;
import com.summit.harnesscore.model.ModelInvoker;
import com.summit.harnesscore.runtime.ExecutionRuntime;
import com.summit.harnesscore.runtime.RuntimeFactory;
import com.summit.harnesscore.runtime.Workspace;
import dev.langchain4j.model.chat.ChatModel;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class ChatAgent implements Agent {
    private final ChatModel chatModel;
    private final RuntimeFactory defaultRuntimeFactory;
    private final AgentConfig agentConfig;
    private final ModelInvoker defaultStreamingModelInvoker;

    @Override
    public String id() {
        return "chat-agent";
    }

    @Override
    public Execution execute(AgentRequest agentRequest) {

        agentRequest.setSystemPrompt(this.agentConfig.systemPrompt());

        Execution execution = ExecutionCreator.create(agentRequest, this);

        Workspace workspace = agentRequest.getWorkspace();

        ExecutionRuntime executionRuntime = defaultRuntimeFactory.createChatModelRuntime(
                chatCommand -> {
                    if (agentRequest.isStreaming()) {
                        return defaultStreamingModelInvoker.invoke(chatCommand);
                    } else return chatModel.chat(chatCommand.chatRequest());
                },
                workspace
        );
        return executionRuntime.execute(execution);
    }


}
