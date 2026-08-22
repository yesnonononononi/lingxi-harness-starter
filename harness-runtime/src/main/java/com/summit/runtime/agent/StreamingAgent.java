package com.summit.runtime.agent;

import com.summit.harnesscore.agent.Agent;
import com.summit.harnesscore.agent.AgentRequest;
import com.summit.harnesscore.agent.Execution;
import com.summit.harnesscore.runtime.ExecutionRuntime;
import com.summit.harnesscore.runtime.Workspace;
import com.summit.harnesscore.tool.ToolRegistry;
import dev.langchain4j.model.chat.StreamingChatModel;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class StreamingAgent implements Agent {
    private final StreamingChatModel model;
    private final Workspace workspace;
    private final ToolRegistry toolRegistry;
    @Override
    public String id() {
        return "streaming-agent";
    }

    @Override
    public Execution execute(AgentRequest agentRequest) {
        Execution execution = ExecutionCreator.create(agentRequest, this);
        ExecutionRuntime executionRuntime = RuntimeCreator.create(model, toolRegistry, workspace);
        return executionRuntime.execute(execution);
    }


}
