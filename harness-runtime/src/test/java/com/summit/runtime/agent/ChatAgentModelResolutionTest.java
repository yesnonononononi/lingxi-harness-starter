package com.summit.runtime.agent;

import com.summit.core.agent.AgentRequest;
import com.summit.core.model.ModelConfig;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class ChatAgentModelResolutionTest {

    private static final ModelConfig APPLICATION_CONFIG = ModelConfig.builder()
            .provider("default")
            .baseUrl("https://app.example")
            .apiKey("app-key")
            .modelName("app-model")
            .timeout(Duration.ofSeconds(30))
            .maxTokens(4096)
            .reasoningEffort("medium")
            .returnThinking(true)
            .sendThinking(true)
            .build();

    @Test
    void usesApplicationConfigWhenRequestCarriesNothing() {
        assertSame(APPLICATION_CONFIG,
                agent().resolveModelConfig(AgentRequest.builder().input("hi").build()));
    }

    @Test
    void usesRequestConfigAsIsWithoutFieldFallback() {
        ModelConfig requestConfig = ModelConfig.builder().modelName("request-model").build();

        ModelConfig resolved = agent().resolveModelConfig(AgentRequest.builder()
                .input("hi")
                .modelConfig(requestConfig)
                .build());

        assertSame(requestConfig, resolved);
        assertNull(resolved.getBaseUrl());
        assertNull(resolved.getApiKey());
        assertNull(resolved.getTimeout());
        assertNull(resolved.getMaxTokens());
        assertFalse(resolved.isSendThinking());
    }

    @Test
    void requestConfigWinsOverModelProvider() {
        ModelConfig requestConfig = ModelConfig.builder().provider("config-provider").modelName("m").build();

        ModelConfig resolved = agent().resolveModelConfig(AgentRequest.builder()
                .input("hi")
                .modelProvider("explicit-provider")
                .modelConfig(requestConfig)
                .build());

        assertSame(requestConfig, resolved);
        assertEquals("config-provider", resolved.getProvider());
    }

    @Test
    void modelProviderSwapsOnlyTheProvider() {
        ModelConfig resolved = agent().resolveModelConfig(AgentRequest.builder()
                .input("hi")
                .modelProvider("my-provider")
                .build());

        assertNotSame(APPLICATION_CONFIG, resolved);
        assertEquals("my-provider", resolved.getProvider());
        assertEquals("https://app.example", resolved.getBaseUrl());
        assertEquals("app-key", resolved.getApiKey());
        assertEquals("app-model", resolved.getModelName());
        assertEquals(Duration.ofSeconds(30), resolved.getTimeout());
        assertEquals(4096, resolved.getMaxTokens());
        assertTrue(resolved.isSendThinking());
    }

    @Test
    void ignoresBlankModelProvider() {
        assertSame(APPLICATION_CONFIG, agent().resolveModelConfig(AgentRequest.builder()
                .input("hi")
                .modelProvider("  ")
                .build()));
    }

    @Test
    void keepsApplicationConfigUntouched() {
        agent().resolveModelConfig(AgentRequest.builder().input("hi").modelProvider("other").build());

        assertEquals("default", APPLICATION_CONFIG.getProvider());
        assertEquals("app-model", APPLICATION_CONFIG.getModelName());
    }

    @Test
    void toleratesMissingApplicationConfig() {
        ChatAgent agent = new TestChatAgent(null);

        ModelConfig resolved = agent.resolveModelConfig(AgentRequest.builder().input("hi").build());
        assertNotNull(resolved);
        assertNull(resolved.getProvider());

        ModelConfig withProvider = agent.resolveModelConfig(AgentRequest.builder()
                .input("hi")
                .modelProvider("custom")
                .build());
        assertEquals("custom", withProvider.getProvider());
    }

    private static ChatAgent agent() {
        return new TestChatAgent(APPLICATION_CONFIG);
    }

    private static final class TestChatAgent extends ChatAgent {
        private TestChatAgent(ModelConfig config) {
            super(null, null, null, null, config);
        }

        @Override
        public String id() {
            return "test-agent";
        }
    }
}
