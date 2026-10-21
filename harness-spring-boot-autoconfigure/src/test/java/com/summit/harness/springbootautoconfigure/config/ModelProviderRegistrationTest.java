package com.summit.harness.springbootautoconfigure.config;

import com.summit.core.model.ModelProviderRegistry;
import com.summit.core.model.ModelProvider;
import com.summit.core.model.DefaultModelProviderNames;
import com.summit.core.model.image.ImageModel;
import com.summit.core.conf.ModelConfig;
import com.summit.core.conversation.api.ChatResponseEntity;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertNotNull;

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
                    ModelProviderRegistry<?> images = context.getBean("imageModelProviderRegistry", ModelProviderRegistry.class);
                    assertTrue(images.contains(DefaultModelProviderNames.DEFAULT_IMAGE));
                    assertFalse(chat.contains(DefaultModelProviderNames.DEFAULT_IMAGE));
                    assertFalse(streaming.contains(DefaultModelProviderNames.DEFAULT_IMAGE));
                    assertFalse(images.contains("default"));
                    assertNotNull(images.create(ModelConfig.builder().provider(DefaultModelProviderNames.DEFAULT_IMAGE)
                            .apiKey("test-key").modelName("image-test").build()));
        }
    }

    @Test
    void applicationCanReplaceTheDefaultImageProviderByName() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.register(CustomImageProvider.class, ModelProviderConfig.class);
            context.refresh();
            var registry = context.getBean("imageModelProviderRegistry", ModelProviderRegistry.class);
            assertSame(context.getBean("commonImageModelProvider"), registry.get(DefaultModelProviderNames.DEFAULT_IMAGE));
            assertSame(CustomImageProvider.MODEL, registry.create(ModelConfig.builder()
                    .provider(DefaultModelProviderNames.DEFAULT_IMAGE).build()));
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class CustomImageProvider {
        static final ImageModel MODEL = prompt -> ChatResponseEntity.builder().build();

        @Bean
        ModelProvider<ImageModel> commonImageModelProvider() {
            return new ModelProvider<>() {
                public String name() { return DefaultModelProviderNames.DEFAULT_IMAGE; }
                public ImageModel create(ModelConfig config) { return MODEL; }
            };
        }
    }
}
