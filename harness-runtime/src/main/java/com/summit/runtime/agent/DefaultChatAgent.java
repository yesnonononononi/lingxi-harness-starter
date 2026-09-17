package com.summit.runtime.agent;

import com.summit.core.model.ModelConfig;
import com.summit.core.model.RequestModelInvokerFactory;
import com.summit.core.runtime.RuntimeFactory;
import com.summit.core.workspace.WorkspaceManager;
import com.summit.runtime.workspace.WorkspaceDestroyer;

/** Default ready-to-use chat agent supplied by LingXi. */
public final class DefaultChatAgent extends ChatAgent {

    public DefaultChatAgent(RuntimeFactory defaultRuntimeFactory,
                            RequestModelInvokerFactory modelInvokerFactory,
                            WorkspaceManager workspaceManager,
                            WorkspaceDestroyer workspaceDestroyer,
                            ModelConfig config
    ) {
        super(defaultRuntimeFactory, modelInvokerFactory, workspaceManager, workspaceDestroyer,config);
    }

    @Override
    public String id() {
        return "chat-agent";
    }
}
