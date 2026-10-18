package com.summit.runtime.agent;

import com.summit.core.agent.AgentRequest;
import com.summit.core.conf.ModelConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ChatAgentModelRoutingTest {
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
                .thinkingType("enabled").thinkingBudgetTokens(2048).maxTokens(4096).build();
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
