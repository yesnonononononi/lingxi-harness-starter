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
    void absentCompactModelReusesOnlyTheChatConnectionAndKeepsCompactBehavior() {
        AgentChatProperties chat = new AgentChatProperties();
        chat.setProvider("business-provider");
        chat.setBaseUrl("https://models.example/v1");
        chat.setApiKey("secret");
        chat.setModelName("main-model");
        chat.setTimeout(Duration.ofSeconds(17));
        chat.setMaxTokens(1234);
        chat.setReasoningEffort("medium");
        chat.setThinkingType("enabled");
        chat.setThinkingBudgetTokens(1024);
        chat.setSendThinking(true);
        chat.setReturnThinking(true);
        chat.setPromptCache(true);
        chat.setPromptCacheTtl("1h");
        chat.setPromptCacheConversation(true);

        ModelConfig compact = configuration.compactContextModelConfig(
                new CompactContextModelProperties(), chat);

        // 连接回落到对话配置：摘要打的就是同一个端点、同一把凭据
        assertEquals(chat.getProvider(), compact.getProvider());
        assertEquals(chat.getBaseUrl(), compact.getBaseUrl());
        assertEquals(chat.getApiKey(), compact.getApiKey());
        assertEquals(chat.getModelName(), compact.getModelName());
        assertEquals(chat.getTimeout(), compact.getTimeout());
        // 调用语义不从对话继承：预算与思考开关归压缩属性，否则摘要会拿思考烧掉自己的输出
        CompactContextModelProperties compactDefaults = new CompactContextModelProperties();
        assertEquals(compactDefaults.getMaxTokens(), compact.getMaxTokens());
        assertEquals("none", compact.getReasoningEffort());
        assertEquals(compactDefaults.isSendThinking(), compact.isSendThinking());
        assertEquals(compactDefaults.isReturnThinking(), compact.isReturnThinking());
        assertEquals(new ModelConfig.AnthropicOptions(null, null, true, null, false), compact.getAnthropicOptions());
    }

    @Test
    void claudeModelsSelectNativeProvidersForChatAndCompaction() {
        AgentChatProperties chat = new AgentChatProperties();
        chat.setBaseUrl("https://models.example/v1");
        chat.setApiKey("test-key");
        chat.setModelName("claude-sonnet-4-6");
        assertEquals("anthropic", configuration.chatModelConfig(chat).getProvider());
        assertEquals("anthropic-compact", configuration.compactContextModelConfig(
                new CompactContextModelProperties(), chat).getProvider());
        CompactContextModelProperties compact = new CompactContextModelProperties();
        compact.setBaseUrl("https://models.example/v1");
        compact.setApiKey("test-key");
        compact.setModelName("claude-sonnet-4-6");
        assertEquals("anthropic-compact", configuration.compactContextModelConfig(compact, chat).getProvider());
        chat.setProvider("default");
        assertEquals("default", configuration.chatModelConfig(chat).getProvider());
    }

    @Test
    void explicitCompactModelRemainsIndependent() {
        AgentChatProperties chat = new AgentChatProperties();
        chat.setModelName("main-model");
        CompactContextModelProperties properties = new CompactContextModelProperties();
        properties.setBaseUrl("https://compact.example/v1");
        properties.setApiKey("compact-secret");
        properties.setModelName("compact-model");
        properties.setThinkingType("enabled");
        properties.setThinkingBudgetTokens(1024);
        properties.setPromptCache(false);
        properties.setPromptCacheTtl("5m");
        properties.setPromptCacheConversation(true);

        ModelConfig compact = configuration.compactContextModelConfig(properties, chat);

        assertEquals("default-compact", compact.getProvider());
        assertEquals("https://compact.example/v1", compact.getBaseUrl());
        assertEquals("compact-secret", compact.getApiKey());
        assertEquals("compact-model", compact.getModelName());
        assertEquals(new ModelConfig.AnthropicOptions("enabled", 1024, false, "5m", true), compact.getAnthropicOptions());
    }
}
