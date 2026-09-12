package com.summit.runtime.agent;

import com.summit.core.agent.Agent;
import com.summit.core.agent.AgentRequest;
import com.summit.core.agent.Execution;
import com.summit.core.model.ChatModel;
import com.summit.core.model.ModelInvoker;
import com.summit.core.runtime.ExecutionRuntime;
import com.summit.core.runtime.RuntimeFactory;
import com.summit.core.runtime.Workspace;
import com.summit.core.workspace.WorkspaceManager;
import com.summit.core.workspace.WorkspaceRecord;
import com.summit.runtime.utils.ExecutionCreator;
import lombok.AllArgsConstructor;

@AllArgsConstructor
public class ChatAgent implements Agent {
    private final ChatModel chatModel;
    private final RuntimeFactory defaultRuntimeFactory;
    private final ModelInvoker defaultStreamingModelInvoker;
    private final WorkspaceManager workspaceManager;





    @Override
    public String id() {
        return "chat-agent";
    }

    @Override
    public Execution execute(AgentRequest agentRequest) {

        Workspace workspace = resolveWorkspace(agentRequest);

        agentRequest.setWorkspace(workspace);

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

    private Workspace resolveWorkspace(AgentRequest request) {
        if (request.getWorkspace() != null) {
            return request.getWorkspace();
        }

        if (request.getWorkspaceRef() != null) {
            return workspaceManager.acquire(request.getWorkspaceRef());
        }
        if (request.getWorkspaceSpec() != null) {
            WorkspaceRecord created = workspaceManager.create(request.getWorkspaceSpec());
            request.setWorkspaceRef(created.ref());
            return workspaceManager.acquire(created.ref());
        }
        throw new IllegalArgumentException("workspace, workspaceRef or workspaceSpec must be provided");
    }


}
