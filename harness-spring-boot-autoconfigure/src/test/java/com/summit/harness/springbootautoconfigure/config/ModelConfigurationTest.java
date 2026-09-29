package com.summit.harness.springbootautoconfigure.config;

import com.summit.core.conf.ModelConfig;
import com.summit.harness.springbootautoconfigure.properties.CompactContextModelProperties;
import com.summit.harness.springbootautoconfigure.properties.agent.AgentChatProperties;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ModelConfigurationTest {

    private final ModelConfiguration configuration = new ModelConfiguration();

    @Test
    void compactModelReusesCompleteChatConfigurationWhenAbsent() {
        AgentChatProperties chat = new AgentChatProperties();
        chat.setProvider("business-provider");
        chat.setBaseUrl("https://models.example/v1");
        chat.setApiKey("secret");
        chat.setModelName("main-model");
        chat.setTimeout(Duration.ofSeconds(17));
        chat.setMaxTokens(1234);
        chat.setReasoningEffort("medium");
        chat.setSendThinking(false);
        chat.setReturnThinking(false);

        ModelConfig compact = configuration.compactContextModelConfig(
                new CompactContextModelProperties(), chat);

        assertEquals(chat.getProvider(), compact.getProvider());
        assertEquals(chat.getBaseUrl(), compact.getBaseUrl());
        assertEquals(chat.getApiKey(), compact.getApiKey());
        assertEquals(chat.getModelName(), compact.getModelName());
        assertEquals(chat.getTimeout(), compact.getTimeout());
        assertEquals(chat.getMaxTokens(), compact.getMaxTokens());
        assertEquals(chat.getReasoningEffort(), compact.getReasoningEffort());
        assertEquals(chat.isSendThinking(), compact.isSendThinking());
        assertEquals(chat.isReturnThinking(), compact.isReturnThinking());
    }

    @Test
    void explicitCompactModelRemainsIndependent() {
        AgentChatProperties chat = new AgentChatProperties();
        chat.setModelName("main-model");
        CompactContextModelProperties properties = new CompactContextModelProperties();
        properties.setBaseUrl("https://compact.example/v1");
        properties.setApiKey("compact-secret");
        properties.setModelName("compact-model");

        ModelConfig compact = configuration.compactContextModelConfig(properties, chat);

        assertEquals("default-compact", compact.getProvider());
        assertEquals("https://compact.example/v1", compact.getBaseUrl());
        assertEquals("compact-secret", compact.getApiKey());
        assertEquals("compact-model", compact.getModelName());
    }
}
