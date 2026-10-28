package com.summit.runtime.model;

import com.summit.core.conf.ModelConfig;
import com.summit.core.model.ModelProvider;
import com.summit.core.model.ModelProviderRegistry;
import com.summit.core.model.chat.ChatModel;
import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class ModelConfigurationCacheTest {
    @Test
    void thinkingModeAndBudgetParticipateInModelCacheIdentity() {
        AtomicInteger created = new AtomicInteger();
        ModelProviderRegistry<ChatModel> providers = new ModelProviderRegistry<>();
        providers.register(new ModelProvider<>() {
            public String name() { return "custom"; }
            public ChatModel create(ModelConfig config) {
                created.incrementAndGet();
                return request -> null;
            }
        });
        ModelConfig config = ModelConfig.builder().baseUrl("http://localhost/v1/").apiKey("test-key")
                .modelName("claude-test").provider("custom").anthropicOptions(ModelConfig.AnthropicOptions.builder()
                        .thinkingType("enabled").thinkingBudgetTokens(2048).build()).build();
        DefaultRequestModelInvokerFactory factory = new DefaultRequestModelInvokerFactory("custom", config,
                providers, new ModelProviderRegistry<>());
        assertSame(factory.select(config), factory.select(config.toBuilder().build()));
        assertNotSame(factory.select(config), factory.select(config.toBuilder().anthropicOptions(config.getAnthropicOptions().toBuilder().thinkingBudgetTokens(4096).build()).build()));
        assertNotSame(factory.select(config), factory.select(config.toBuilder().anthropicOptions(config.getAnthropicOptions().toBuilder().thinkingType("adaptive").build()).build()));
        assertNotSame(factory.select(config), factory.select(config.toBuilder().anthropicOptions(
                config.getAnthropicOptions().toBuilder().promptCache(true).build()).build()));
        assertNotSame(factory.select(config), factory.select(config.toBuilder().anthropicOptions(
                config.getAnthropicOptions().toBuilder().promptCacheTtl("1h").build()).build()));
        assertNotSame(factory.select(config), factory.select(config.toBuilder().anthropicOptions(
                config.getAnthropicOptions().toBuilder().promptCacheConversation(true).build()).build()));
        assertNotSame(factory.select(config), factory.select(config.toBuilder().maxRetries(0).build()));
        assertEquals(7, created.get());
        var original = factory.select(config);
        config.setMaxRetries(0);
        assertNotSame(original, factory.select(config));
        config.setMaxRetries(3);
        assertSame(original, factory.select(config));
    }
}
