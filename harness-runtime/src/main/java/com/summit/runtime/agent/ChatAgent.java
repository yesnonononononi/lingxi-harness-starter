package com.summit.runtime.agent;

import com.summit.core.agent.Agent;
import com.summit.core.agent.AgentRequest;
import com.summit.core.agent.Execution;
import com.summit.core.conf.ModelConfig;
import com.summit.core.model.DefaultModelProviderNames;
import com.summit.core.model.RequestModelInvokerFactory;
import com.summit.core.runtime.ExecutionRuntime;
import com.summit.core.runtime.RuntimeFactory;
import com.summit.core.runtime.workspace.Workspace;
import com.summit.core.workspace.WorkspaceManager;
import com.summit.core.workspace.WorkspaceSpec;
import com.summit.runtime.utils.ExecutionCreator;
import lombok.AllArgsConstructor;
import org.jspecify.annotations.NonNull;


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
    private final ModelConfig modelConfig;


    @Override
    public Execution execute(AgentRequest agentRequest) {
        if (agentRequest.getMessages() == null || agentRequest.getMessages().isEmpty()) {
            throw new IllegalArgumentException("AgentRequest.messages must contain the conversation context");
        }

        Workspace workspace = resolveWorkspace(agentRequest);

        RequestModelInvokerFactory.Selection model = findSelection(agentRequest);


        Execution execution = ExecutionCreator.create(agentRequest, this, model.streaming());

        ExecutionRuntime executionRuntime = defaultRuntimeFactory.createChatModelRuntime(
                execution.getId(),
                model.invoker(),
                workspace
        );
        return executionRuntime.execute(execution);
    }

    @Override
    public Execution execute(Execution execution) {
        if (execution == null) {
            throw new IllegalArgumentException("Execution must not be null");
        }
        ExecutionRuntime executionRuntime = prepareExecutionRuntime(execution);
        return executionRuntime.execute(execution);
    }

    private ExecutionRuntime prepareExecutionRuntime(Execution execution) {
        AgentRequest agentRequest = execution.getAgentRequest();
        if (agentRequest == null) {
            throw new IllegalArgumentException("Execution.agentRequest must not be null");
        }

        Workspace workspace = resolveWorkspace(agentRequest);
        RequestModelInvokerFactory.Selection model = findSelection(agentRequest);
        return defaultRuntimeFactory.createChatModelRuntime(
                execution.getId(),
                model.invoker(),
                workspace
        );
    }

    protected RequestModelInvokerFactory.Selection findSelection(AgentRequest agentRequest) {
        return this.modelInvokerFactory.select(resolveModelConfig(agentRequest));
    }

    /**
     * Resolves the model configuration for one request. The rules are deliberately flat — no
     * field-level fallback and no precedence chain:
     * <ol>
     *   <li>{@code modelConfig} is set — used as is when it names a provider; otherwise it is
     *       completed by {@link #completeRequestConfig(ModelConfig)} and {@code modelProvider} is ignored.</li>
     *   <li>{@code modelProvider} is set — the application configuration with that provider.</li>
     *   <li>neither is set — the application configuration as is.</li>
     * </ol>
     */
    protected ModelConfig resolveModelConfig(@NonNull AgentRequest agentRequest) {
        ModelConfig requestConfig = agentRequest.getModelConfig();
        if (requestConfig != null) {
            return completeRequestConfig(requestConfig);
        }
        ModelConfig base = applicationModelConfig();

        if (agentRequest.getModelProvider() == null || agentRequest.getModelProvider().isBlank()) {
            return base;
        }
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

    /**
     * Completes a request-level configuration the caller did not bind to a provider. Such a
     * configuration carries no protocol of its own, so the framework derives one: the three
     * attributes a model cannot be built without are required up front, and {@code returnThinking}
     * selects the streaming protocol because thinking output is delivered as a stream.
     */
    protected ModelConfig completeRequestConfig(@NonNull ModelConfig requestConfig) {
        if (requestConfig.getProvider() != null && !requestConfig.getProvider().isBlank()) {
            return requestConfig;
        }
        return ModelConfig.builder()
                .provider(requestConfig.isReturnThinking()
                        ? DefaultModelProviderNames.DEFAULT_STREAMING
                        : DefaultModelProviderNames.DEFAULT)
                .baseUrl(ensureNotBlank(requestConfig.getBaseUrl(),"baseUrl"))
                .apiKey(ensureNotBlank(requestConfig.getApiKey(), "apiKey"))
                .modelName(ensureNotBlank(requestConfig.getModelName(), "modelName"))
                .timeout(requestConfig.getTimeout())
                .maxTokens(requestConfig.getMaxTokens())
                .reasoningEffort(requestConfig.getReasoningEffort())
                .returnThinking(requestConfig.isReturnThinking())
                .sendThinking(requestConfig.isSendThinking())
                .build();
    }


    private ModelConfig applicationModelConfig() {
        if (this.modelConfig == null) {
            throw new IllegalStateException("No application model configuration is available: "
                    + "configure lingxi.agent.model.conf.chat or pass AgentRequest.modelConfig");
        }
        return this.modelConfig;
    }

    private static String ensureNotBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Value must be non-blank: " + name);
        }
        return value;
    }

    /**
     * Resolves the workspace of one request without ever owning its lifetime.
     *
     * <p>A live {@link Workspace} handed in by the caller is used as is. A spec
     * is resolved through {@link WorkspaceManager#acquire(WorkspaceSpec)}, which
     * reuses the sandbox already provisioned for that configuration and creates
     * one only the first time the configuration is seen. The workspace therefore
     * survives the request, so the next turn — and the next session on the same
     * project — starts from a warm toolchain instead of a cold container.</p>
     */
    protected Workspace resolveWorkspace(AgentRequest request) {
        Workspace workspace = request.getWorkspace();
        if (workspace != null) {
            return workspace;
        }
        WorkspaceSpec spec = request.getWorkspaceSpec();
        if (spec == null) {
            return null;
        }
        return workspaceManager.acquire(spec);
    }


}
