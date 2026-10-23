package com.summit.runtime.agent;

import com.summit.core.agent.AgentRequest;
import com.summit.core.conf.ModelConfig;
import com.summit.core.conf.ModelRetryConfig;
import com.summit.core.model.RequestModelInvokerFactory;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ChatAgentModelRoutingTest {
    @Test
    void runtimeRetryOwnershipDisablesSdkRetriesWithoutChangingTheStoredConfig() {
        AtomicReference<ModelConfig> selected = new AtomicReference<>();
        RequestModelInvokerFactory factory = new RequestModelInvokerFactory() {
            public Selection select(ModelConfig config) {
                selected.set(config);
                return new Selection("test", command -> null, false);
            }
            public Selection select(String provider) { return select(ModelConfig.builder().provider(provider).build()); }
        };
        ChatAgent selectedAgent = new ChatAgent(null, factory, null, null, null, null) {
            public String id() { return "retry-test"; }
        };
        ModelConfig config = ModelConfig.builder().provider("test").maxRetries(3).build();
        selectedAgent.findSelection(AgentRequest.builder().modelConfig(config)
                .retryConfig(ModelRetryConfig.builder().enabled(true).build()).build());
        assertEquals(0, selected.get().getMaxRetries());
        assertEquals(3, config.getMaxRetries());
        selectedAgent.findSelection(AgentRequest.builder().modelConfig(config).build());
        assertEquals(3, selected.get().getMaxRetries());
    }
    private final ChatAgent agent = new ChatAgent(null, null, null, null, null, null) {
        public String id() { return "routing-test"; }
    };

    @Test
    void claudePrefixSelectsNativeAnthropicForBothModes() {
        assertEquals("anthropic", resolve("claude-opus-4-6", false, null).getProvider());
        assertEquals("anthropic-streaming", resolve("  CLAUDE-sonnet-4-6  ", true, " ").getProvider());
        assertEquals("default", resolve("gpt-test", false, null).getProvider());
        assertEquals("default-streaming", resolve("custom-gateway-model", true, null).getProvider());
    }

    @Test
    void explicitProviderWinsAndInferencePreservesParameters() {
        assertEquals("default-streaming", resolve("claude-opus-4-6", true, "default-streaming").getProvider());
        ModelConfig config = ModelConfig.builder().baseUrl("http://localhost/v1").apiKey("key")
                .modelName("claude-opus-4-6").returnThinking(true).reasoningEffort("high")
                .anthropicOptions(ModelConfig.AnthropicOptions.builder()
                        .thinkingType("enabled").thinkingBudgetTokens(2048).build()).maxTokens(4096).build();
        ModelConfig resolved = agent.resolveModelConfig(AgentRequest.builder().modelConfig(config)
                .modelProvider("ignored").build());
        assertEquals("anthropic-streaming", resolved.getProvider());
        assertEquals(config.toBuilder().provider("anthropic-streaming").build(), resolved);
        assertNull(config.getProvider());
    }

    private ModelConfig resolve(String model, boolean streaming, String provider) {
        return agent.resolveModelConfig(AgentRequest.builder().modelConfig(ModelConfig.builder()
                .baseUrl("http://localhost/v1").apiKey("key").modelName(model)
                .returnThinking(streaming).provider(provider).build()).build());
    }
}
