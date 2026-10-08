package com.summit.runtime.agent;

import com.summit.core.conf.ModelConfig;
import com.summit.core.mcp.ScopeMcpProvider;
import com.summit.core.model.RequestModelInvokerFactory;
import com.summit.core.runtime.RuntimeFactory;
import com.summit.core.runtime.loop.ExecutionRepository;
import com.summit.core.runtime.loop.ExecutionControl;
import com.summit.core.workspace.WorkspaceManager;

/** Default ready-to-use chat agent supplied by LingXi. */
public final class DefaultChatAgent extends ChatAgent {

    public DefaultChatAgent(RuntimeFactory defaultRuntimeFactory,
                            RequestModelInvokerFactory modelInvokerFactory,
                            WorkspaceManager workspaceManager,
                            ModelConfig config,
                            ExecutionRepository executionRepository,
                            ExecutionControl executionControl) {
        super(defaultRuntimeFactory, modelInvokerFactory, workspaceManager, config, executionRepository, executionControl);
    }

    public DefaultChatAgent(RuntimeFactory defaultRuntimeFactory,
                            RequestModelInvokerFactory modelInvokerFactory,
                            WorkspaceManager workspaceManager,
                            ModelConfig config,
                            ScopeMcpProvider scopeMcpProvider,
                            ExecutionRepository executionRepository,
                            ExecutionControl executionControl
    ) {
        super(defaultRuntimeFactory, modelInvokerFactory, workspaceManager, config, scopeMcpProvider, executionRepository, executionControl);
    }

    @Override
    public String id() {
        return "chat-agent";
    }
}
