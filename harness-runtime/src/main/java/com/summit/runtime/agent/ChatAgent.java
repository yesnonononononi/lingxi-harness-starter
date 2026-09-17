package com.summit.runtime.agent;

import com.summit.core.agent.Agent;
import com.summit.core.agent.AgentRequest;
import com.summit.core.agent.Execution;
import com.summit.core.model.ModelConfig;
import com.summit.core.model.RequestModelInvokerFactory;
import com.summit.core.runtime.ExecutionRuntime;
import com.summit.core.runtime.RuntimeFactory;
import com.summit.core.runtime.workspace.Workspace;
import com.summit.core.workspace.WorkspaceManager;
import com.summit.core.workspace.WorkspaceSpec;
import com.summit.runtime.utils.ExecutionCreator;
import com.summit.runtime.workspace.WorkspaceExecutionScope;
import com.summit.runtime.workspace.WorkspaceDestroyer;
import lombok.AllArgsConstructor;


/**
 * Base implementation for conversational agents.
 *
 * <p>Subclasses provide their identity and may override the protected resolution hooks while the
 * request/runtime/workspace lifecycle remains consistent.</p>
 */
@AllArgsConstructor
public abstract class ChatAgent implements Agent {
    protected final RuntimeFactory defaultRuntimeFactory;
    protected final RequestModelInvokerFactory modelInvokerFactory;
    protected final WorkspaceManager workspaceManager;
    protected final WorkspaceDestroyer workspaceDestroyer;
    private final ModelConfig modelConfig;




    @Override
    public Execution execute(AgentRequest agentRequest) {
        try (WorkspaceExecutionScope scope = createExecutionScope(agentRequest)) {

            Workspace workspace = scope == null ? resolveExistingWorkspace(agentRequest) : scope.workspace();

            RequestModelInvokerFactory.Selection model = findSelection(agentRequest);


            Execution execution = ExecutionCreator.create(agentRequest, this, model.streaming());

            ExecutionRuntime executionRuntime = defaultRuntimeFactory.createChatModelRuntime(
                    agentRequest.sessionIdOrDefault(),
                    model.invoker(),
                    workspace
            );
            return executionRuntime.execute(execution);
        }
    }

    protected RequestModelInvokerFactory.Selection findSelection(AgentRequest agentRequest){
        return this.modelInvokerFactory.select(resolveModelConfig(agentRequest));
    }

    /**
     * Resolves the model configuration for one request. The rules are deliberately flat — no
     * field-level fallback and no precedence chain:
     * <ol>
     *   <li>{@code modelConfig} is set — used as is, {@code modelProvider} is ignored.</li>
     *   <li>{@code modelProvider} is set — the application configuration with that provider.</li>
     *   <li>neither is set — the application configuration as is.</li>
     * </ol>
     */
    protected ModelConfig resolveModelConfig(AgentRequest agentRequest) {
        ModelConfig requestConfig = agentRequest.getModelConfig();
        if (requestConfig != null) {
            return requestConfig;
        }
        if (agentRequest.getModelProvider() == null || agentRequest.getModelProvider().isBlank()) {
            return this.modelConfig != null ? this.modelConfig : ModelConfig.builder().build();
        }
        ModelConfig base = this.modelConfig != null ? this.modelConfig : ModelConfig.builder().build();
        return ModelConfig.builder()
                .provider(agentRequest.getModelProvider().trim())
                .baseUrl(base.getBaseUrl())
                .apiKey(base.getApiKey())
                .modelName(base.getModelName())
                .timeout(base.getTimeout())
                .maxTokens(base.getMaxTokens())
                .reasoningEffort(base.getReasoningEffort())
                .returnThinking(base.isReturnThinking())
                .sendThinking(base.isSendThinking())
                .build();
    }



    protected WorkspaceExecutionScope createExecutionScope(AgentRequest request) {
        if (request.getWorkspace() != null) {
            return null;
        }
        WorkspaceSpec spec = request.getWorkspaceSpec();
        if (spec != null && spec.workspaceRef() == null) {
            return WorkspaceExecutionScope.open(workspaceManager, spec, workspaceDestroyer);
        }
        return null;
    }

    protected Workspace resolveExistingWorkspace(AgentRequest request) {
        Workspace workspace = request.getWorkspace();
        if(workspace != null)return workspace;

        WorkspaceSpec workspaceSpec = request.getWorkspaceSpec();
        if(workspaceSpec == null)throw new IllegalArgumentException("at least a workspace conf is acquired");
        return workspaceManager.acquire(workspaceSpec.workspaceRef());
    }


}
