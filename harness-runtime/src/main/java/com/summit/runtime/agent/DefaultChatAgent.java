package com.summit.runtime.agent;

import com.summit.core.conf.ModelConfig;
import com.summit.core.mcp.ScopeMcpProvider;
import com.summit.core.model.RequestModelInvokerFactory;
import com.summit.core.runtime.RuntimeFactory;
import com.summit.core.workspace.WorkspaceManager;

/** Default ready-to-use chat agent supplied by LingXi. */
public final class DefaultChatAgent extends ChatAgent {

    public DefaultChatAgent(RuntimeFactory defaultRuntimeFactory,
                            RequestModelInvokerFactory modelInvokerFactory,
                            WorkspaceManager workspaceManager,
                            ModelConfig config) {
        super(defaultRuntimeFactory, modelInvokerFactory, workspaceManager, config);
    }

    public DefaultChatAgent(RuntimeFactory defaultRuntimeFactory,
                            RequestModelInvokerFactory modelInvokerFactory,
                            WorkspaceManager workspaceManager,
                            ModelConfig config,
                            ScopeMcpProvider scopeMcpProvider
    ) {
        super(defaultRuntimeFactory, modelInvokerFactory, workspaceManager, config, scopeMcpProvider);
    }

    @Override
    public String id() {
        return "chat-agent";
    }
}
