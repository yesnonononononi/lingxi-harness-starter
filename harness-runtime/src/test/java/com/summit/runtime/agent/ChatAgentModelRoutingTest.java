package com.summit.runtime.agent;

import com.summit.core.agent.AgentRequest;
import com.summit.core.conf.ModelConfig;
import com.summit.core.conf.ModelRetryConfig;
import com.summit.core.conf.SensitiveDataSpec;
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
        ModelConfig config = ModelConfig.builder().provider("test").maxRetries(3).build();
        ChatAgent selectedAgent = DefaultChatAgent.builder().modelInvokerFactory(factory)
                .modelConfigSensitiveDataProvider((id, version) -> config).build();
        selectedAgent.findSelection(AgentRequest.builder().modelConfigSpec(spec())
                .retryConfig(ModelRetryConfig.builder().enabled(true).build()).build());
        assertEquals(0, selected.get().getMaxRetries());
        assertEquals(3, config.getMaxRetries());
        selectedAgent.findSelection(AgentRequest.builder().modelConfigSpec(spec()).build());
        assertEquals(3, selected.get().getMaxRetries());
    }

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
        ChatAgent agent = DefaultChatAgent.builder().modelConfigSensitiveDataProvider((id, version) -> config).build();
        ModelConfig resolved = agent.resolveModelConfig(AgentRequest.builder().modelConfigSpec(spec())
                .modelProvider("ignored").build());
        assertEquals("anthropic-streaming", resolved.getProvider());
        assertEquals(config.toBuilder().provider("anthropic-streaming").build(), resolved);
        assertNull(config.getProvider());
    }

    private ModelConfig resolve(String model, boolean streaming, String provider) {
        ModelConfig config = ModelConfig.builder()
                .baseUrl("http://localhost/v1").apiKey("key").modelName(model)
                .returnThinking(streaming).provider(provider).build();
        return DefaultChatAgent.builder().modelConfigSensitiveDataProvider((id, version) -> config).build()
                .resolveModelConfig(AgentRequest.builder().modelConfigSpec(spec()).build());
    }

    private static SensitiveDataSpec spec() {
        return SensitiveDataSpec.builder().id(31L).version(7).build();
    }
}
