package com.summit.runtime.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.summit.core.agent.AgentRequest;
import com.summit.core.agent.Execution;
import com.summit.core.agent.ExecutionState;
import com.summit.core.conf.McpConfig;
import com.summit.core.conf.McpTransport;
import com.summit.core.conf.ModelConfig;
import com.summit.core.conf.SensitiveDataSpec;
import com.summit.core.json.ExecutionJson;
import com.summit.core.mcp.McpToolScope;
import com.summit.core.model.RequestModelInvokerFactory;
import com.summit.runtime.workspace.DefaultWorkspaceResolver;
import com.summit.runtime.workspace.LocalWorkspaceProvider;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class ChatAgentSensitiveDataTest {
    private static final ModelConfig DEFAULT = ModelConfig.builder().provider("application").build();
    private static final SensitiveDataSpec MODEL = SensitiveDataSpec.builder().id(2113133924577333249L).version(7).build();
    private static final SensitiveDataSpec MCP = SensitiveDataSpec.builder().id(99L).version(3).build();

    @Test
    void restoredExecutionResolvesBothExactRevisionsWithoutPersistingResolvedSecrets() throws Exception {
        ObjectMapper mapper = ExecutionJson.newObjectMapper();
        ModelConfig model = ModelConfig.builder().provider("test").apiKey("model-secret-canary").build();
        McpConfig mcp = servers();
        assertTrue(mapper.writeValueAsString(model).contains("model-secret-canary"));
        assertTrue(mapper.writeValueAsString(mcp).contains("mcp-secret-canary"));
        List<String> references = new ArrayList<>();
        AtomicReference<ModelConfig> selectedModel = new AtomicReference<>();
        AtomicReference<McpConfig> selectedMcp = new AtomicReference<>();
        ChatAgent agent = DefaultChatAgent.builder()
                .modelConfig(DEFAULT)
                .modelConfigSensitiveDataProvider((id, version) -> {
                    references.add("model:" + id + ":" + version);
                    return model;
                })
                .mcpConfigSensitiveDataProvider((id, version) -> {
                    references.add("mcp:" + id + ":" + version);
                    return mcp;
                })
                .modelInvokerFactory(new RequestModelInvokerFactory() {
                    public Selection select(ModelConfig config) {
                        selectedModel.set(config);
                        return new Selection("test", command -> null, false);
                    }
                    public Selection select(String provider) { throw new AssertionError("configuration is required"); }
                })
                .workspaceResolver(new DefaultWorkspaceResolver(List.of(new LocalWorkspaceProvider())))
                .scopeMcpProvider(config -> {
                    selectedMcp.set(config);
                    return McpToolScope.EMPTY;
                })
                .defaultRuntimeFactory((invoker, workspace, scope) -> execution -> execution)
                .build();
        Execution original = execution();
        Execution restored = mapper.readValue(mapper.writeValueAsString(original), Execution.class);
        agent.execute(restored);

        assertEquals(List.of("model:2113133924577333249:7", "mcp:99:3"), references);
        assertSame(model, selectedModel.get());
        assertSame(mcp, selectedMcp.get());
        String snapshot = mapper.writeValueAsString(restored);
        assertEquals(mapper.readTree(mapper.writeValueAsString(original)), mapper.readTree(snapshot));
        assertFalse(snapshot.contains("model-secret-canary"));
        assertFalse(snapshot.contains("mcp-secret-canary"));
    }

    @Test
    void explicitModelReferenceRequiresAProviderEvenWithApplicationDefaults() {
        ChatAgent agent = DefaultChatAgent.builder().modelConfig(DEFAULT).build();
        assertThrows(IllegalStateException.class, () -> agent.resolveModelConfig(execution().getAgentRequest()));
    }

    @Test
    void missingModelRevisionCannotFallBackToApplicationDefaults() {
        ChatAgent agent = DefaultChatAgent.builder().modelConfig(DEFAULT)
                .modelConfigSensitiveDataProvider((id, version) -> null).build();
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> agent.resolveModelConfig(execution().getAgentRequest()));
        assertTrue(failure.getMessage().contains("version=7"));
    }

    @Test
    void applicationProviderOverrideStillWorksWithoutAReference() {
        ChatAgent agent = DefaultChatAgent.builder().modelConfig(DEFAULT).build();
        assertSame(DEFAULT, agent.resolveModelConfig(AgentRequest.builder().build()));
        assertEquals("override", agent.resolveModelConfig(AgentRequest.builder().modelProvider(" override ").build()).getProvider());
        assertEquals("application", DEFAULT.getProvider());
        assertThrows(IllegalStateException.class,
                () -> DefaultChatAgent.builder().build().resolveModelConfig(AgentRequest.builder().build()));
    }

    @Test
    void explicitMcpReferenceRequiresAProvider() {
        ChatAgent agent = DefaultChatAgent.builder().build();
        assertThrows(IllegalStateException.class, () -> agent.openMcpScope(execution(), null));
    }

    @Test
    void missingMcpRevisionCannotSilentlyRemoveTools() {
        ChatAgent agent = DefaultChatAgent.builder().mcpConfigSensitiveDataProvider((id, version) -> null).build();
        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> agent.openMcpScope(execution(), null));
        assertTrue(failure.getMessage().contains("version=3"));
    }

    @Test
    void absentMcpReferenceAndResolvedEmptyServersIntentionallyUseEmptyScope() {
        ChatAgent agent = DefaultChatAgent.builder().mcpConfigSensitiveDataProvider((id, version) -> new McpConfig()).build();
        assertSame(McpToolScope.EMPTY, agent.openMcpScope(execution(), null));
        Execution withoutMcp = Execution.builder().agentRequest(AgentRequest.builder().build()).build();
        assertSame(McpToolScope.EMPTY, DefaultChatAgent.builder().build().openMcpScope(withoutMcp, null));
    }

    private static Execution execution() {
        return Execution.builder().id("sensitive-test").executionState(ExecutionState.SUSPENDED)
                .agentRequest(AgentRequest.builder().modelConfigSpec(MODEL).mcpConfigSpec(MCP).build()).build();
    }

    private static McpConfig servers() {
        McpConfig config = new McpConfig();
        config.setMcp(List.of(new McpConfig.MCP("test", null, McpTransport.STREAMABLE_HTTP,
                new McpConfig.StreamableHttp("https://example.invalid", Map.of("Authorization", "mcp-secret-canary"), null, null), 100)));
        return config;
    }
}
