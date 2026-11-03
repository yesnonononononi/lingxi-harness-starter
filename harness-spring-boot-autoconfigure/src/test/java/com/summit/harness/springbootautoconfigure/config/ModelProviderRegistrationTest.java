package com.summit.harness.springbootautoconfigure.config;

import com.summit.core.model.ModelProviderRegistry;
import com.summit.core.model.ModelProvider;
import com.summit.core.model.DefaultModelProviderNames;
import com.summit.core.model.image.ImageModel;
import com.summit.core.conf.ModelConfig;
import com.summit.core.model.ModelChatCommand;
import com.summit.core.model.RequestModelInvokerFactory;
import com.summit.core.model.image.ImageGenerationOptions;
import com.summit.core.conversation.api.ChatRequestEntity;
import com.summit.core.conversation.message.UserMessageEntity;
import com.summit.core.conversation.api.ChatResponseEntity;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ModelProviderRegistrationTest {
    @Test
    void imagePropertiesConnectThroughTheRegistryToGenerationAndUsage() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<String> request = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        server.createContext("/v1/images/generations", exchange -> {
            request.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] response = ("{\"created\":1,\"data\":[{\"b64_json\":\"aW1hZ2U=\"}],"
                    + "\"usage\":{\"input_tokens\":15,\"output_tokens\":80,\"total_tokens\":95,"
                    + "\"input_tokens_details\":{\"text_tokens\":5,\"image_tokens\":10},"
                    + "\"output_tokens_details\":{\"image_tokens\":80}}}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            try (var output = exchange.getResponseBody()) { output.write(response); }
        });
        server.start();
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("image-test", Map.of(
                    "lingxi.agent.model.conf.image.base-url", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                    "lingxi.agent.model.conf.image.api-key", "image-key",
                    "lingxi.agent.model.conf.image.model-name", "image-test",
                    "lingxi.agent.model.conf.image.timeout", "7s",
                    "lingxi.agent.model.conf.image.max-retries", "0",
                    "lingxi.agent.model.conf.image.quality", "high",
                    "lingxi.agent.model.conf.image.size", "1024x1024",
                    "lingxi.agent.model.conf.image.count", "2")));
            context.register(ModelProviderConfig.class, ModelConfiguration.class);
            context.refresh();
            var config = context.getBean("imageModelConfig", ModelConfig.class);
            assertEquals(DefaultModelProviderNames.DEFAULT_IMAGE, config.getProvider());
            assertEquals(Duration.ofSeconds(7), config.getTimeout());
            assertEquals(0, config.getMaxRetries());
            assertEquals(2, config.getImageOptions().count());
            var registry = context.getBean("imageModelProviderRegistry", ModelProviderRegistry.class);
            var result = ((ImageModel) registry.create(config)).generate("draw a tree");
            var body = new ObjectMapper().readTree(request.get());
            assertEquals("Bearer image-key", authorization.get());
            assertEquals("image-test", body.path("model").asText());
            assertEquals("high", body.path("quality").asText());
            assertEquals("1024x1024", body.path("size").asText());
            assertEquals(2, body.path("n").asInt());
            assertEquals("aW1hZ2U=", result.getMeta().getImages().getFirst().getBase64Data());
            assertEquals(95, result.getTokenUsage().getTotalTokens());
            assertEquals(80, result.getTokenUsage().getOutputTokens().getOutputTokens());
            assertEquals(80, result.getTokenUsage().getOutputTokens().getImageTokens());
            assertEquals(5, result.getTokenUsage().getInputTokens().getTextTokens());
            var selection = context.getBean(RequestModelInvokerFactory.class).select(DefaultModelProviderNames.DEFAULT_IMAGE);
            var overridden = selection.invoker().invoke(() -> ModelChatCommand.builder().responseId("image-response")
                    .chatRequest(ChatRequestEntity.builder().messages(List.of(UserMessageEntity.from("wide tree")))
                            .imageGenerationOptions(new ImageGenerationOptions("1536x1024", null, 1)).build()).build());
            body = new ObjectMapper().readTree(request.get());
            assertEquals("image-response", overridden.getResponseId());
            assertEquals("Bearer image-key", authorization.get());
            assertEquals("image-test", body.path("model").asText());
            assertEquals("1536x1024", body.path("size").asText());
            assertEquals("high", body.path("quality").asText());
            assertEquals(1, body.path("n").asInt());
        } finally {
            server.stop(0);
        }
    }

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
