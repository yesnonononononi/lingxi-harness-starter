package com.summit.runtime.agent;

import com.summit.core.agent.Agent;
import com.summit.core.agent.AgentRequest;
import com.summit.core.agent.Execution;
import com.summit.core.agent.ExecutionState;
import com.summit.core.conf.McpConfig;
import com.summit.core.conf.ModelConfig;
import com.summit.core.mcp.ScopeMcpProvider;
import com.summit.core.mcp.McpToolScope;
import com.summit.core.model.DefaultModelProviderNames;
import com.summit.core.model.RequestModelInvokerFactory;
import com.summit.core.runtime.ExecutionRuntime;
import com.summit.core.runtime.RuntimeFactory;
import com.summit.core.runtime.loop.ExecutionRepository;
import com.summit.core.runtime.loop.ExecutionControl;
import com.summit.core.runtime.workspace.Workspace;
import com.summit.core.workspace.BasicWorkspaceSpec;
import com.summit.core.workspace.WorkspaceManager;
import com.summit.core.workspace.WorkspaceSpec;
import com.summit.runtime.workspace.LocalWorkspaceProvider;
import lombok.AllArgsConstructor;
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
public abstract class ChatAgent implements Agent {
    protected final RuntimeFactory defaultRuntimeFactory;
    protected final RequestModelInvokerFactory modelInvokerFactory;
    protected final WorkspaceManager workspaceManager;
    private final ModelConfig modelConfig;
    private final ScopeMcpProvider scopeMcpProvider;
    private final ExecutionRepository executionRepository;
    private final ExecutionControl executionControl;

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

    public ChatAgent(RuntimeFactory defaultRuntimeFactory,
                     RequestModelInvokerFactory modelInvokerFactory,
                     WorkspaceManager workspaceManager,
                     ModelConfig modelConfig,
                     ExecutionRepository executionRepository,
                     ExecutionControl executionControl
    ) {
        this(defaultRuntimeFactory,
                modelInvokerFactory,
                workspaceManager,
                modelConfig,
                null,
                executionRepository,
                executionControl
        );
    }


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

        McpToolScope mcpToolScope = openMcpScope(execution);

        return defaultRuntimeFactory.createRuntime(
                model.invoker(),
                workspace,
                mcpToolScope
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
        if (this.modelConfig == null) {
            throw new IllegalStateException("No application model configuration is available");
        }

        if (agentRequest.getModelProvider() == null || agentRequest.getModelProvider().isBlank()) {
            return this.modelConfig;
        }
        return this.modelConfig.toBuilder().provider(agentRequest.getModelProvider().trim()).build();
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
        ensureNotBlank(requestConfig.getBaseUrl(), "baseUrl");
        ensureNotBlank(requestConfig.getApiKey(), "apiKey");
        ensureNotBlank(requestConfig.getModelName(), "modelName");
        return requestConfig.toBuilder()
                .provider(requestConfig.isReturnThinking()
                        ? DefaultModelProviderNames.DEFAULT_STREAMING : DefaultModelProviderNames.DEFAULT)
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
     * <p>The request's spec is resolved through {@link WorkspaceManager#acquire(WorkspaceSpec)}, which
     * reuses the sandbox already provisioned for that configuration and creates
     * one only the first time the configuration is seen. The workspace therefore
     * survives the request, so the next turn — and the next session on the same
     * project — starts from a warm toolchain instead of a cold container.</p>
     */
    protected Workspace resolveWorkspace(AgentRequest request) {

        WorkspaceSpec spec = request.getWorkspaceSpec();
        if (spec == null) {
            spec = new BasicWorkspaceSpec(LocalWorkspaceProvider.TYPE, System.getProperty("user.dir"));
        }
        return workspaceManager.acquire(spec);
    }


    /**
     * Opens the MCP tool scope of this request, or reuses the one kept by a previous suspension of
     * the same execution. A request without MCP configuration gets the shared empty scope.
     *
     * <p>Every path that yields no MCP tools is logged. A request that declares servers but still
     * ends up empty is a configuration fault, and failing silently there turns a one-character
     * mistake into "the tools just vanished" with nothing in the log to point at.</p>
     */
    protected McpToolScope openMcpScope(Execution execution) {
        McpConfig mcpConfig = execution.getAgentRequest().getMcpConfig();
        if (mcpConfig == null || mcpConfig.getMcp() == null || mcpConfig.getMcp().isEmpty()) {
            // The request declared no servers: absence is intentional, no diagnostic needed.
            return McpToolScope.EMPTY;
        }
        if (scopeMcpProvider == null) {
            return McpToolScope.EMPTY;
        }
        return mcpScopes.computeIfAbsent(execution.getId(), ignored -> scopeMcpProvider.openScope(mcpConfig));
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
