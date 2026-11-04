package com.summit.harness.springbootautoconfigure.config;

import com.summit.core.agent.Image;
import com.summit.core.exception.NonRetriableModelException;
import com.summit.core.exception.RetriableModelException;
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
import com.summit.core.conversation.event.RuntimeEventPublisher;
import com.summit.core.runtime.loop.ExecutionControlSignal;
import com.summit.core.runtime.loop.suspension.ExecutionInterruptedException;
import com.summit.runtime.model.StreamingModelResponseBehaveDecider;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import openAi.image.DefaultImageModel;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("openAi.image-test", Map.of(
                    "lingxi.agent.model.conf.image.base-url", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                    "lingxi.agent.model.conf.image.api-key", "openAi.image-key",
                    "lingxi.agent.model.conf.image.model-name", "openAi.image-test",
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
            var model = assertInstanceOf(DefaultImageModel.class, registry.create(config));
            var result = model.generate("draw a tree");
            var body = new ObjectMapper().readTree(request.get());
            assertEquals("Bearer openAi.image-key", authorization.get());
            assertEquals("openAi.image-test", body.path("model").asText());
            assertEquals("high", body.path("quality").asText());
            assertEquals("1024x1024", body.path("size").asText());
            assertEquals(2, body.path("n").asInt());
            assertEquals("aW1hZ2U=", result.getMeta().getImages().getFirst().getBase64Data());
            assertEquals(95, result.getTokenUsage().getTotalTokens());
            assertEquals(80, result.getTokenUsage().getOutputTokens().getOutputTokens());
            assertEquals(80, result.getTokenUsage().getOutputTokens().getImageTokens());
            assertEquals(5, result.getTokenUsage().getInputTokens().getTextTokens());
            var selection = context.getBean(RequestModelInvokerFactory.class).select(DefaultModelProviderNames.DEFAULT_IMAGE);
            var overridden = selection.invoker().invoke(() -> ModelChatCommand.builder().responseId("openAi.image-response")
                    .chatRequest(ChatRequestEntity.builder().messages(List.of(UserMessageEntity.from("wide tree")))
                            .imageGenerationOptions(new ImageGenerationOptions("1536x1024", null, 1)).build()).build());
            body = new ObjectMapper().readTree(request.get());
            assertEquals("openAi.image-response", overridden.getResponseId());
            assertEquals("Bearer openAi.image-key", authorization.get());
            assertEquals("openAi.image-test", body.path("model").asText());
            assertEquals("1536x1024", body.path("size").asText());
            assertEquals("high", body.path("quality").asText());
            assertEquals(1, body.path("n").asInt());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void netImageEditsSendMultipartOptionsAndClassifyTransientErrors() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger attempts = new AtomicInteger();
        AtomicReference<String> request = new AtomicReference<>();
        AtomicReference<String> contentType = new AtomicReference<>();
        AtomicReference<String> customHeader = new AtomicReference<>();
        server.createContext("/gateway/v1/images/edits", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            request.set(body);
            contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            customHeader.set(exchange.getRequestHeaders().getFirst("X-Route"));
            int attempt = attempts.incrementAndGet();
            int status = body.contains("invalid edit") ? 400 : attempt == 1 ? 503 : 200;
            String responseBody = body.contains("malformed response") ? "{" : body.contains("empty response") ? "{}"
                    : body.contains("unmasked edit") ? """
                    {"data":[{"b64_json":"aW1hZ2U=","url":"https://images.example/edited.webp"}]}
                    """ : """
                    {"data":[{"url":"https://images.example/edited.webp","revised_prompt":"polished"}],
                    "output_format":"webp"}
                    """;
            byte[] response = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, response.length);
            try (var output = exchange.getResponseBody()) { output.write(response); }
        });
        server.start();
        try (var context = new AnnotationConfigApplicationContext(ModelProviderConfig.class)) {
            var registry = context.getBean("imageModelProviderRegistry", ModelProviderRegistry.class);
            var model = assertInstanceOf(DefaultImageModel.class, registry.create(ModelConfig.builder()
                    .provider(DefaultModelProviderNames.DEFAULT_IMAGE)
                    .baseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/gateway/v1/images/generations/")
                    .modelName("openAi.image-test").apiKey("local-test").maxRetries(1)
                    .imageOptions(new ModelConfig.ImageOptions("high", "1024x1024", Map.of("X-Route", "images"), 2))
                    .build()));
            Image source = Image.from("c291cmNl", "image/jpeg");
            Image mask = Image.from("bWFzaw==");
            var options = new ImageGenerationOptions("1536x1024", null, null, "transparent", "webp");
            assertThrows(RetriableModelException.class, () -> model.edit(source, mask, "polish", options));
            assertEquals(1, attempts.get());
            var response = model.edit(source, mask, "polish", options);
            assertEquals(2, attempts.get());
            assertEquals("images", customHeader.get());
            String boundary = contentType.get().substring("multipart/form-data; boundary=".length());
            assertTrue(request.get().endsWith("--" + boundary + "--\r\n"));
            assertTrue(request.get().contains("name=\"image\"; filename=\"image.jpg\"\r\nContent-Type: image/jpeg\r\n\r\nsource"));
            assertTrue(request.get().contains("name=\"mask\"; filename=\"mask.png\"\r\nContent-Type: image/png\r\n\r\nmask"));
            assertTrue(request.get().contains("name=\"quality\"\r\n\r\nhigh"));
            assertTrue(request.get().contains("name=\"size\"\r\n\r\n1536x1024"));
            assertTrue(request.get().contains("name=\"background\"\r\n\r\ntransparent"));
            assertTrue(request.get().contains("name=\"output_format\"\r\n\r\nwebp"));
            assertFalse(request.get().contains("name=\"n\""));
            var image = response.getAiMessageEntity().getImages().getFirst();
            assertSame(image, response.getMeta().getImages().getFirst());
            assertEquals("https://images.example/edited.webp", image.getUrl().toString());
            assertEquals("image/webp", image.mimeType());
            assertEquals("polished", image.getRevisedPrompt());

            var unmasked = model.edit(source, "unmasked edit\nkeep the frame \"as-is\"",
                    ImageGenerationOptions.builder().outputFormat("webp").build())
                    .getMeta().getImages().getFirst();
            assertFalse(request.get().contains("name=\"mask\""));
            assertTrue(request.get().contains("unmasked edit\nkeep the frame \"as-is\""));
            assertEquals("aW1hZ2U=", unmasked.getBase64Data());
            assertNull(unmasked.getUrl());
            assertNull(unmasked.getMimeType());
            assertFalse(model.toString().contains("local-test"));
            assertThrows(NonRetriableModelException.class, () -> model.edit(source, "invalid edit"));
            assertThrows(NonRetriableModelException.class, () -> model.edit(source, "malformed response"));
            assertThrows(NonRetriableModelException.class, () -> model.edit(source, "empty response"));
            assertThrows(NonRetriableModelException.class, () -> model.edit(Image.from("!"), "polish"));
            assertThrows(NonRetriableModelException.class, () -> model.generate(" "));
            assertThrows(UnsupportedOperationException.class,
                    () -> model.edit(source, "batch", new ImageGenerationOptions(null, null, 2)));
            assertEquals(6, attempts.get());
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
                    assertTrue(chat.contains("net-chat"));
                    assertTrue(streaming.contains("net-streaming"));
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
                            .apiKey("test-key").modelName("openAi.image-test").build()));
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

    @Test
    void netStreamingCanSuspendBeforeTheFirstDelta() throws Exception {
        CountDownLatch requestArrived = new CountDownLatch(1);
        CountDownLatch releaseServer = new CountDownLatch(1);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/provider/v1/chat/completions", exchange -> {
            try (exchange) {
                exchange.getRequestBody().readAllBytes();
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, 0);
                requestArrived.countDown();
                releaseServer.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
        server.start();
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.register(ModelProviderConfig.class, ModelConfiguration.class);
            context.refresh();
            try {
                ModelConfig config = ModelConfig.builder().provider("net-streaming").modelName("mock")
                        .baseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/provider/v1")
                        .apiKey("local-test").timeout(Duration.ofSeconds(8)).build();
                RequestModelInvokerFactory.Selection selection = context.getBean(RequestModelInvokerFactory.class).select(config);
                ExecutionControlSignal signal = new ExecutionControlSignal("idle-stream");
                StreamingModelResponseBehaveDecider handler = new StreamingModelResponseBehaveDecider(
                        new RuntimeEventPublisher(List.of()),
                        StreamingModelResponseBehaveDecider.StreamingResponseContext.builder()
                                .executionId("idle-stream").responseId("1").future(new CompletableFuture<>()).build(),
                        signal);
                ModelChatCommand command = ModelChatCommand.builder().streaming(true).responseId("1").signal(signal)
                        .streamingChatResponseHandler(handler)
                        .chatRequest(ChatRequestEntity.builder().messages(List.of(UserMessageEntity.from("hello"))).build())
                        .build();
                CompletableFuture<ChatResponseEntity> outcome = CompletableFuture.supplyAsync(() -> {
                    try {
                        return selection.invoker().invoke(() -> command);
                    } catch (RuntimeException error) {
                        throw error;
                    } catch (Exception error) {
                        throw new CompletionException(error);
                    }
                });

                assertTrue(requestArrived.await(5, TimeUnit.SECONDS));
                signal.requireSuspend();
                ExecutionException failure = assertThrows(ExecutionException.class, () -> outcome.get(3, TimeUnit.SECONDS));
                ExecutionInterruptedException interrupted = assertInstanceOf(ExecutionInterruptedException.class, failure.getCause());
                assertEquals(ExecutionInterruptedException.Kind.SUSPEND, interrupted.kind());
            } finally {
                releaseServer.countDown();
            }
        } finally {
            server.stop(0);
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
