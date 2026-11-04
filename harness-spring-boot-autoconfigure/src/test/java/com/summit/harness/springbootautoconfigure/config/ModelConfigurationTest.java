package com.summit.harness.springbootautoconfigure.config;

import com.summit.core.agent.AgentRequest;
import com.summit.core.agent.Execution;
import com.summit.core.compact.ContextAttachmentProvider;
import com.summit.core.compact.ContextSqueezeRequest;
import com.summit.core.conf.ModelConfig;
import com.summit.core.conversation.event.RuntimeEventPublisher;
import com.summit.core.conversation.message.Message;
import com.summit.core.conversation.message.UserMessageEntity;
import com.summit.core.tool.ToolDefinition;
import com.summit.core.tool.ToolExecuteResult;
import com.summit.core.tool.ToolRegistry;
import com.summit.harness.springbootautoconfigure.properties.CompactContextModelProperties;
import com.summit.harness.springbootautoconfigure.properties.agent.AgentChatProperties;
import com.summit.runtime.agent.AgentConfig;
import com.summit.runtime.conversation.DefaultConversationManager;
import com.summit.runtime.loop.BoundaryChecker;
import openAi.OpenAiTokenizer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelConfigurationTest {

    private final ModelConfiguration configuration = new ModelConfiguration();

    @Test
    void tokenizerCountsOnlyWhitelistedRegisteredToolsInTheContextBudget() {
        var estimator = assertInstanceOf(OpenAiTokenizer.class, configuration.tokenEstimator());
        var conversations = new ConversationConfig();
        var tokenizer = conversations.tokenizer(estimator);
        var allowed = tool("lookup", "Search the workspace for a matching project file.");
        var hidden = tool("hidden", "A different private capability.");
        var beans = new DefaultListableBeanFactory();
        beans.registerSingleton("toolRegistry", new ToolRegistry(List.of(allowed, hidden)));
        List<Message> messages = List.of(UserMessageEntity.from("Find the config"));
        int messagesOnly = tokenizer.count(messages);
        int withLookup = tokenizer.count(messages, List.of(allowed));
        assertTrue(withLookup > messagesOnly);
        assertTrue(tokenizer.count(messages, List.of(allowed, hidden)) > withLookup);
        var config = AgentConfig.builder().maxTokens(withLookup).build();
        var reporter = conversations.contextUsageReporter(tokenizer, config, new RuntimeEventPublisher(List.of()),
                new AgentChatProperties(), beans.getBeanProvider(ToolRegistry.class));
        var visibleExecution = Execution.create(AgentRequest.builder().messages(messages).toolList(List.of("lookup")).build(), "test");
        var hiddenExecution = Execution.create(AgentRequest.builder().messages(messages).build(), "test");
        assertEquals(withLookup, reporter.report(visibleExecution).tokenCount());
        assertEquals(messagesOnly, reporter.report(hiddenExecution).tokenCount());
        var manager = new DefaultConversationManager(ContextAttachmentProvider.NONE);
        var checker = new BoundaryChecker(config, tokenizer, manager, null, null, reporter);
        assertEquals(ContextSqueezeRequest.prune(), checker.shouldSqueezeContext(manager, visibleExecution));
        assertEquals(ContextSqueezeRequest.none(), checker.shouldSqueezeContext(manager, hiddenExecution));
    }

    private ToolDefinition<?> tool(String name, String description) {
        return ToolDefinition.builder().id(name).name(name).description(description).maxOutput(64).timeout(5L)
                .parametersJsonSchema("{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"}}}")
                .executor(ignored -> ToolExecuteResult.success("ok")).build();
    }

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
        assertNull(compact.getReasoningEffort());
        assertEquals(compactDefaults.isSendThinking(), compact.isSendThinking());
        assertEquals(compactDefaults.isReturnThinking(), compact.isReturnThinking());
        assertEquals(new ModelConfig.AnthropicOptions("disabled", null, true, null, false), compact.getAnthropicOptions());
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
    void applicationCompactionSelectsSynchronousProvidersForChatAliases() {
        AgentChatProperties chat = new AgentChatProperties();
        chat.setProvider("default-streaming");
        assertEquals("default-compact", configuration.compactContextModelConfig(
                new CompactContextModelProperties(), chat).getProvider());
        chat.setProvider("net-streaming");
        assertEquals("net-chat", configuration.compactContextModelConfig(
                new CompactContextModelProperties(), chat).getProvider());
        chat.setProvider("anthropic-streaming");
        assertEquals("anthropic-compact", configuration.compactContextModelConfig(
                new CompactContextModelProperties(), chat).getProvider());
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
