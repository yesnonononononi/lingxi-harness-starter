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
                .modelName("claude-test").provider("custom").thinkingType("enabled")
                .thinkingBudgetTokens(2048).build();
        DefaultRequestModelInvokerFactory factory = new DefaultRequestModelInvokerFactory("custom", config,
                providers, new ModelProviderRegistry<>());
        assertSame(factory.select(config), factory.select(config.toBuilder().build()));
        assertNotSame(factory.select(config), factory.select(config.toBuilder().thinkingBudgetTokens(4096).build()));
        assertNotSame(factory.select(config), factory.select(config.toBuilder().thinkingType("adaptive").build()));
        assertEquals(3, created.get());
    }
}
