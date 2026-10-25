package com.summit.runtime.agent;

import com.summit.core.agent.Agent;
import com.summit.core.agent.AgentRequest;
import com.summit.core.agent.Execution;
import com.summit.core.agent.ExecutionState;
import com.summit.core.conf.McpConfig;
import com.summit.core.conf.ModelConfig;
import com.summit.core.conf.SensitiveDataProvider;
import com.summit.core.conf.SensitiveDataSpec;
import com.summit.core.mcp.McpToolScope;
import com.summit.core.mcp.ScopeMcpProvider;
import com.summit.core.model.ModelProviderRouting;
import com.summit.core.model.RequestModelInvokerFactory;
import com.summit.core.runtime.ExecutionRuntime;
import com.summit.core.runtime.RuntimeFactory;
import com.summit.core.runtime.loop.ExecutionControl;
import com.summit.core.runtime.loop.ExecutionRepository;
import com.summit.core.runtime.workspace.Workspace;
import com.summit.core.workspace.BasicWorkspaceSpec;
import com.summit.core.workspace.WorkspaceResolver;
import com.summit.core.workspace.WorkspaceSpec;
import com.summit.runtime.workspace.LocalWorkspaceProvider;
import lombok.AllArgsConstructor;
import lombok.experimental.SuperBuilder;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;


/**
 * Base implementation for conversational agents.
 *
 * <p>Subclasses provide their identity and may override the protected resolution hooks while the
 * request/runtime/workspace lifecycle remains consistent.</p>
 */
@Slf4j
@AllArgsConstructor
@SuperBuilder
public abstract class ChatAgent implements Agent {
    protected final RuntimeFactory defaultRuntimeFactory;
    protected final RequestModelInvokerFactory modelInvokerFactory;
    protected final WorkspaceResolver workspaceResolver;
    private final ScopeMcpProvider scopeMcpProvider;
    private final ModelConfig modelConfig;
    private final ExecutionRepository executionRepository;
    private final ExecutionControl executionControl;
    private final SensitiveDataProvider<McpConfig> mcpConfigSensitiveDataProvider;
    private final SensitiveDataProvider<ModelConfig> modelConfigSensitiveDataProvider;

    /**
     * MCP scopes of the executions currently in flight, so a resumed execution reuses the scope its
     * suspension kept instead of opening a second set of connections.
     *
     * <p>Ownership contract: an entry is released only from a terminal state, so an execution that
     * is suspended and then abandoned keeps its scope. How long a suspension may last is a business
     * decision the framework does not model (see {@code docs/adr/loop-suspension-boundary.md}), so
     * there is deliberately no expiry here. A caller that gives up on a suspended execution must
     * cancel it so the state becomes terminal, and must resume it through {@link #execute(Execution)}
     * so the retained scope is reused and eventually released.</p>
     */
    private final Map<String, McpToolScope> mcpScopes = new ConcurrentHashMap<>();


    @Override
    public Execution createExecution(AgentRequest agentRequest) {
        Execution execution = Execution.create(agentRequest, id());

        executionRepository.save(execution);

        return execution;
    }

    @Override
    public Execution execute(AgentRequest agentRequest) {
        return this.execute(this.createExecution(agentRequest));
    }

    @Override
    public Execution execute(Execution execution) {
        if (execution == null) {
            throw new IllegalArgumentException("Execution must not be null");
        }
        if (execution.getExecutionState() != ExecutionState.CREATED && !execution.isSuspended()) {
            throw new IllegalStateException("Only a created or suspended execution can run: " + execution.getId());
        }
        try {
            ExecutionRuntime executionRuntime;
            try {
                executionRuntime = prepareExecutionRuntime(execution);
            } catch (RuntimeException e) {
                // Only fresh initialization belongs here; the loop owns its own outcome.
                if (execution.getExecutionState() == ExecutionState.CREATED) {
                    try {
                        executionControl.fail(execution, e).run();
                    } catch (RuntimeException saveFailure) {
                        if (saveFailure != e) e.addSuppressed(saveFailure);
                    }
                }
                throw e;
            }
            return executionRuntime.execute(execution);
        } finally {
            releaseIfTerminal(execution);
        }
    }

    private ExecutionRuntime prepareExecutionRuntime(Execution execution) {
        AgentRequest agentRequest = execution.getAgentRequest();

        Workspace workspace = resolveWorkspace(agentRequest);

        RequestModelInvokerFactory.Selection model = findSelection(agentRequest);

        execution.setStreaming(model.streaming());

        McpToolScope mcpToolScope = openMcpScope(execution, workspace);

        return defaultRuntimeFactory.createRuntime(
                model.invoker(),
                workspace,
                mcpToolScope
        );
    }

    protected RequestModelInvokerFactory.Selection findSelection(AgentRequest agentRequest) {
        ModelConfig config = resolveModelConfig(agentRequest);
        if (agentRequest.getRetryConfig() != null && agentRequest.getRetryConfig().isEnabled()) {
            // Runtime retries own control, attempt identity and budget; SDK retries must not multiply them.
            config = config.toBuilder().maxRetries(0).build();
        }
        return this.modelInvokerFactory.select(config);
    }

    /**
     * Resolves the model configuration for one request. The rules are deliberately flat — no
     * field-level fallback and no precedence chain:
     * <ol>
     *   <li>{@code modelConfigSpec} is set — its exact revision is required and used as is when it names a provider; otherwise it is
     *       completed by {@link #completeRequestConfig(ModelConfig)} and {@code modelProvider} is ignored.</li>
     *   <li>{@code modelProvider} is set — the application configuration with that provider.</li>
     *   <li>neither is set — the application configuration as is.</li>
     * </ol>
     */
    protected ModelConfig resolveModelConfig(@NonNull AgentRequest agentRequest) {
        SensitiveDataSpec modelConfigSpec = agentRequest.getModelConfigSpec();
        if (modelConfigSpec != null) {
            if (modelConfigSensitiveDataProvider == null) {
                throw new IllegalStateException("A model configuration reference requires a SensitiveDataProvider<ModelConfig>");
            }
            ModelConfig requestConfig = modelConfigSensitiveDataProvider.get(modelConfigSpec.getId(), modelConfigSpec.getVersion());
            if (requestConfig == null) {
                throw new IllegalStateException("Model configuration revision is unavailable: id="
                        + modelConfigSpec.getId() + ", version=" + modelConfigSpec.getVersion());
            }
            return completeRequestConfig(requestConfig);
        }

        if (this.modelConfig == null) {
            throw new IllegalStateException("A request without a model configuration reference requires an application model configuration");
        }
        String provider = agentRequest.getModelProvider();
        return provider == null || provider.isBlank() ? this.modelConfig
                : this.modelConfig.toBuilder().provider(provider.trim()).build();
    }

    /**
     * Completes a request-level configuration the caller did not bind to a provider. Such a
     * configuration carries no protocol of its own, so the model name selects the provider family.
     * {@code returnThinking} retains the existing synchronous/streaming selection convention.
     */
    protected ModelConfig completeRequestConfig(@NonNull ModelConfig requestConfig) {
        if (requestConfig.getProvider() != null && !requestConfig.getProvider().isBlank()) {
            return requestConfig;
        }
        ensureNotBlank(requestConfig.getBaseUrl(), "baseUrl");
        ensureNotBlank(requestConfig.getApiKey(), "apiKey");
        ensureNotBlank(requestConfig.getModelName(), "modelName");
        return requestConfig.toBuilder()
                .provider(ModelProviderRouting.chatProvider(null, requestConfig.getModelName(),
                        requestConfig.isReturnThinking()))
                .build();
    }


    private static void ensureNotBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Value must be non-blank: " + name);
        }
    }

    /**
     * Resolves the workspace of one request without ever owning its lifetime.
     * A request without a spec uses the local process working directory ({@code user.dir}).
     *
     * <p>The resolver routes the spec to its provider on every call. Providers own any resource
     * reuse and cleanup; completing a request never destroys the returned workspace.</p>
     */
    protected Workspace resolveWorkspace(AgentRequest request) {

        WorkspaceSpec spec = request.getWorkspaceSpec();
        if (spec == null) {
            spec = new BasicWorkspaceSpec(LocalWorkspaceProvider.TYPE, System.getProperty("user.dir"));
        }
        return workspaceResolver.resolve(spec);
    }


    /**
     * Opens the MCP tool scope of this request, or reuses the one kept by a previous suspension of
     * the same execution. A request without MCP configuration gets the shared empty scope.
     *
     * <p>An explicit reference must resolve its exact revision; missing configuration must not
     * silently remove tools from a resumed execution.</p>
     */
    protected McpToolScope openMcpScope(Execution execution, Workspace workspace) {
        SensitiveDataSpec mcpConfigSpec = execution.getAgentRequest().getMcpConfigSpec();
        if (mcpConfigSpec == null) {
            return McpToolScope.EMPTY;
        }
        if (mcpConfigSensitiveDataProvider == null) {
            throw new IllegalStateException("An MCP configuration reference requires a SensitiveDataProvider<McpConfig>");
        }
        McpConfig mcpConfig = mcpConfigSensitiveDataProvider.get(mcpConfigSpec.getId(), mcpConfigSpec.getVersion());
        if (mcpConfig == null) {
            throw new IllegalStateException("MCP configuration revision is unavailable: id="
                    + mcpConfigSpec.getId() + ", version=" + mcpConfigSpec.getVersion());
        }
        if (mcpConfig.getMcp() == null || mcpConfig.getMcp().isEmpty()) {
            return McpToolScope.EMPTY;
        }
        if (scopeMcpProvider == null) {
            throw new IllegalStateException("Configured MCP servers require a ScopeMcpProvider");
        }
        return mcpScopes.computeIfAbsent(execution.getId(), ignored -> scopeMcpProvider.openScope(mcpConfig, workspace));
    }

    /**
     * Closes the MCP scope once the execution can no longer continue; a suspension keeps it.
     */
    private void releaseIfTerminal(Execution execution) {
        ExecutionState state = execution.getExecutionState();

        if (state != ExecutionState.COMPLETED && state != ExecutionState.CANCELLED
                && state != ExecutionState.FAILED) return;

        McpToolScope scope = mcpScopes.remove(execution.getId());
        if (scope != null) {
            scope.close();
        }
    }

}
