package com.summit.harness.springbootautoconfigure.config;

import com.summit.core.model.ModelProviderRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelProviderRegistrationTest {
    @Test
    void registersBothProtocolsWithoutChatStreamingNameCollisions() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
                    context.register(ModelProviderConfig.class);
                    context.refresh();
                    ModelProviderRegistry<?> chat = context.getBean("chatModelProviderRegistry", ModelProviderRegistry.class);
                    ModelProviderRegistry<?> streaming = context.getBean("streamingModelProviderRegistry", ModelProviderRegistry.class);
                    assertTrue(chat.contains("default"));
                    assertTrue(chat.contains("default-compact"));
                    assertTrue(chat.contains("anthropic"));
                    assertTrue(chat.contains("anthropic-compact"));
                    assertTrue(streaming.contains("default-streaming"));
                    assertTrue(streaming.contains("anthropic-streaming"));
                    assertFalse(chat.contains("anthropic-streaming"));
        }
    }
}
