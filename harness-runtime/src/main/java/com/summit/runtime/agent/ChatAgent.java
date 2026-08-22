package com.summit.runtime.agent;

import com.summit.harnesscore.agent.Agent;
import com.summit.harnesscore.agent.AgentRequest;
import com.summit.harnesscore.agent.Execution;
import com.summit.harnesscore.runtime.ExecutionRuntime;
import com.summit.harnesscore.runtime.Workspace;
import com.summit.harnesscore.tool.ToolRegistry;
import dev.langchain4j.model.chat.ChatModel;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class ChatAgent implements Agent {
    private final ChatModel model;
    private final Workspace workspace;
    private final ToolRegistry toolRegistry;
    @Override
    public String id() {
        return "chat-agent";
    }

    @Override
    public Execution execute(AgentRequest agentRequest) {
        Execution execution = ExecutionCreator.create(agentRequest, this);
        ExecutionRuntime executionRuntime = RuntimeCreator.create(model, toolRegistry, workspace);
        return executionRuntime.execute(execution);
    }
}
