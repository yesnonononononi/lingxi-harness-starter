package com.summit.harness.springbootautoconfigure.config;

import anthropic.AnthropicChatModel;
import com.summit.core.conf.ModelConfig;
import com.summit.core.model.ModelProvider;
import openAi.DefaultChatModel;
import openAi.image.DefaultImageModel;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;

class ModelHttpClientFactoryRegistrationTest {
    @Test
    void defaultTransportUsesJdkHttpClient() {
        try (var context = new AnnotationConfigApplicationContext(ModelProviderConfig.class)) {
            assertEquals(Duration.ofSeconds(15), context.getBean("netModelHttpClient", HttpClient.class)
                    .connectTimeout().orElseThrow());
        }
    }

    @Test
    void allBuiltInChannelsUseNetModelsWithTheNamedClientOverride() {
        try (var client = HttpClient.newHttpClient();
             var context = new AnnotationConfigApplicationContext()) {
            context.registerBean("netModelHttpClient", HttpClient.class, () -> client);
            context.register(ModelProviderConfig.class);
            context.refresh();
            assertSame(client, context.getBean("netModelHttpClient", HttpClient.class));
            var config = ModelConfig.builder().apiKey("test").modelName("test").maxTokens(1024).build();
            Map<String, Class<?>> providers = Map.of(
                    "chatModelModelProvider", DefaultChatModel.class,
                    "streamingChatModelModelProvider", DefaultChatModel.class,
                    "compactContextChatModelProvider", DefaultChatModel.class,
                    "anthropicChatModelProvider", AnthropicChatModel.class,
                    "anthropicStreamingModelProvider", AnthropicChatModel.class,
                    "anthropicCompactContextModelProvider", AnthropicChatModel.class,
                    "commonImageModelProvider", DefaultImageModel.class);
            providers.forEach((name, model) ->
                    assertInstanceOf(model, context.getBean(name, ModelProvider.class).create(config), name));
        }
    }
}
