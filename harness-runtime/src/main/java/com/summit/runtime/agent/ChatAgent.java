package com.summit.runtime.agent;

import com.summit.core.agent.Agent;
import com.summit.core.agent.AgentRequest;
import com.summit.core.agent.Execution;
import com.summit.core.model.ChatModel;
import com.summit.core.model.ModelInvoker;
import com.summit.core.runtime.ExecutionRuntime;
import com.summit.core.runtime.RuntimeFactory;
import com.summit.core.runtime.Workspace;
import com.summit.runtime.utils.ExecutionCreator;
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

        // NOTE: AgentRequest.systemPrompt is a user-supplied custom prompt and must be passed
        // through verbatim; the framework default template is assembled by SystemPromptAssembler
        // inside DefaultConversationManager (start segment), so it is NOT forced here anymore.

        Workspace workspace = agentRequest.getWorkspace();

        Execution execution = ExecutionCreator.create(agentRequest, this);

        ExecutionRuntime executionRuntime = defaultRuntimeFactory.createChatModelRuntime(
                agentRequest.sessionIdOrDefault(),
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
