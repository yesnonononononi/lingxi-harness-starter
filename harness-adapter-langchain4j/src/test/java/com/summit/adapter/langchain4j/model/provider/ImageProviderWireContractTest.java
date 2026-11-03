package com.summit.adapter.langchain4j.model.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.summit.core.agent.Image;
import com.summit.core.conf.ModelConfig;
import com.summit.core.model.image.ImageGenerationOptions;
import dev.langchain4j.http.client.jdk.JdkHttpClient;
import dev.langchain4j.http.client.jdk.JdkHttpClientBuilder;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.ArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Uses the real SDK against a local server, including multipart uploads and retries. */
class ImageProviderWireContractTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void perCallSpecificationsInheritDefaultsAndReuseOneTransportWithoutMutatingConfiguration() throws Exception {
        AtomicInteger transports = new AtomicInteger();
        var provider = new CommonImageModelProvider(() -> new JdkHttpClientBuilder() {
            @Override
            public JdkHttpClient build() {
                transports.incrementAndGet();
                return super.build();
            }
        });
        try (Endpoint endpoint = new Endpoint()) {
            var config = config(endpoint).imageOptions(new ModelConfig.ImageOptions("high", "1024x1024",
                    Map.of("X-Route", "images"), 2)).build();
            var model = provider.create(config);
            assertEquals(2, model.generate("default").getMeta().getImages().size());
            assertSpecification(endpoint.take(), "1024x1024", "high", 2);
            assertEquals(2, model.generate("wide", new ImageGenerationOptions("1536x1024", null, null))
                    .getMeta().getImages().size());
            assertSpecification(endpoint.take(), "1536x1024", "high", 2);
            assertEquals(1, model.generate("one", new ImageGenerationOptions(null, "low", 1))
                    .getMeta().getImages().size());
            assertSpecification(endpoint.take(), "1024x1024", "low", 1);
            model.generate("legacy explicit count", 1);
            assertSpecification(endpoint.take(), "1024x1024", "high", 1);
            assertEquals("1024x1024", config.getImageOptions().size());
            assertEquals(2, config.getImageOptions().count());
            // Callers retain their mutable config, but already-created models keep a private snapshot.
            config.setModelName("changed");
            config.setImageOptions(null);
            model.generate("default again", ImageGenerationOptions.EMPTY);
            assertSpecification(endpoint.take(), "1024x1024", "high", 2);
            assertEquals(1, transports.get());
        }
    }

    @Test
    void concurrentOverridesAreIsolatedAndLeaveTheDefaultModelIntact() throws Exception {
        try (Endpoint endpoint = new Endpoint(); var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var model = new CommonImageModelProvider().create(config(endpoint)
                    .imageOptions(new ModelConfig.ImageOptions("high", "1024x1024", null)).build());
            CountDownLatch start = new CountDownLatch(1);
            var futures = new ArrayList<Future<?>>();
            for (int i = 0; i < 8; i++) {
                String size = i % 2 == 0 ? "1536x1024" : "1024x1536";
                futures.add(workers.submit(() -> {
                    start.await();
                    model.generate(size, new ImageGenerationOptions(size, null, 1));
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> future : futures) future.get(10, TimeUnit.SECONDS);
            for (int i = 0; i < 8; i++) {
                var body = mapper.readTree(endpoint.take().body());
                assertEquals(body.path("prompt").asText(), body.path("size").asText());
                assertEquals("high", body.path("quality").asText());
            }
            model.generate("default");
            var body = mapper.readTree(endpoint.take().body());
            assertEquals("1024x1024", body.path("size").asText());
        }
    }

    private void assertSpecification(Request request, String size, String quality, int count) throws Exception {
        var body = mapper.readTree(request.body());
        assertEquals("image-test", body.path("model").asText());
        assertEquals(size, body.path("size").asText());
        assertEquals(quality, body.path("quality").asText());
        assertEquals(count, body.path("n").asInt());
        assertEquals("images", request.route());
        assertEquals("Bearer test-key", request.authorization());
    }

    @Test
    void singleAndBatchGenerationCarryOptionsAndParseImagesAndUsage() throws Exception {
        try (Endpoint endpoint = new Endpoint()) {
            var model = new CommonImageModelProvider().create(config(endpoint)
                    .imageOptions(new ModelConfig.ImageOptions("high", "1024x1024", Map.of("X-Route", "images"))).build());
            var single = model.generate("draw a tree");
            Request first = endpoint.take();
            var body = mapper.readTree(first.body());
            assertEquals("/v1/images/generations", first.path());
            assertEquals("Bearer test-key", first.authorization());
            assertEquals("images", first.route());
            assertEquals("image-test", body.path("model").asText());
            assertEquals("draw a tree", body.path("prompt").asText());
            assertEquals("high", body.path("quality").asText());
            assertEquals("1024x1024", body.path("size").asText());
            assertEquals(1, body.path("n").asInt());
            assertEquals("https://images.example/tree", single.getMeta().getImages().getFirst().getUrl().toString());
            assertEquals("revised tree", single.getMeta().getImages().getFirst().getRevisedPrompt());
            assertEquals(95, single.getTokenUsage().getTotalTokens());
            assertEquals(15, single.getTokenUsage().getInputTokens().getInputTokens());
            assertEquals(10, single.getTokenUsage().getInputTokens().getImageTokens());
            assertEquals(80, single.getTokenUsage().getOutputTokens().getOutputTokens());
            assertEquals(80, single.getTokenUsage().getOutputTokens().getImageTokens());

            var batch = model.generate("draw a tree", 2);
            assertEquals(2, mapper.readTree(endpoint.take().body()).path("n").asInt());
            assertEquals(2, batch.getMeta().getImages().size());
            assertEquals("c2Vjb25k", batch.getMeta().getImages().get(1).getBase64Data());
            assertEquals("image/jpeg", batch.getMeta().getImages().get(1).mimeType());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void absentOrBlankImageOptionsUseSdkDefaults(boolean blankOptions) throws Exception {
        try (Endpoint endpoint = new Endpoint()) {
            var builder = config(endpoint);
            if (blankOptions) builder.imageOptions(new ModelConfig.ImageOptions(" ", "", null));
            new CommonImageModelProvider().create(builder.build()).generate("draw");
            Request request = endpoint.take();
            var body = mapper.readTree(request.body());
            assertFalse(body.has("size"));
            assertFalse(body.has("quality"));
            assertNull(request.route());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void editsUploadImageAndOptionalMaskAsMultipart(boolean masked) throws Exception {
        try (Endpoint endpoint = new Endpoint()) {
            var model = new CommonImageModelProvider().create(config(endpoint)
                    .imageOptions(new ModelConfig.ImageOptions("high", "1024x1024", Map.of("X-Route", "edits"))).build());
            Image image = Image.from(Base64.getEncoder().encodeToString("original-image-bytes".getBytes(StandardCharsets.UTF_8)), "image/jpeg");
            Image mask = Image.from(Base64.getEncoder().encodeToString("mask-image-bytes".getBytes(StandardCharsets.UTF_8)));
            var response = masked ? model.edit(image, mask, "replace sky") : model.edit(image, "replace sky");
            Request request = endpoint.take();
            assertEquals("/v1/images/edits", request.path());
            assertTrue(request.contentType().startsWith("multipart/form-data;"));
            assertEquals("edits", request.route());
            assertEquals("Bearer test-key", request.authorization());
            assertTrue(request.body().contains("name=\"image\"; filename=\"image.jpg\""), request.body());
            assertTrue(request.body().contains("image/jpeg"));
            assertTrue(request.body().contains("original-image-bytes"));
            assertEquals(masked, request.body().contains("name=\"mask\""));
            assertEquals(masked, request.body().contains("mask-image-bytes"));
            for (String value : new String[]{"name=\"model\"", "image-test", "name=\"prompt\"", "replace sky",
                    "name=\"quality\"", "high", "name=\"size\"", "1024x1024"}) {
                assertTrue(request.body().contains(value), request.body());
            }
            assertEquals(1, response.getMeta().getImages().size());
            assertEquals(95, response.getTokenUsage().getTotalTokens());
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1})
    void configuredRetryCountControlsHttpAttempts(int retries) throws Exception {
        try (Endpoint endpoint = new Endpoint()) {
            endpoint.failures.set(1);
            var model = new CommonImageModelProvider().create(config(endpoint).maxRetries(retries).build());
            var options = new ImageGenerationOptions("1536x1024", null, null);
            if (retries == 0) assertThrows(RuntimeException.class, () -> model.generate("draw", options));
            else assertEquals(1, model.generate("draw", options).getMeta().getImages().size());
            assertEquals(retries + 1, endpoint.requests.size());
            for (Request request : endpoint.requests) assertEquals("1536x1024", mapper.readTree(request.body()).path("size").asText());
        }
    }

    private ModelConfig.ModelConfigBuilder config(Endpoint endpoint) {
        return ModelConfig.builder().baseUrl(endpoint.baseUrl()).apiKey("test-key").modelName("image-test")
                .timeout(Duration.ofSeconds(5)).maxRetries(0);
    }

    private record Request(String path, String contentType, String authorization, String route, String body) {}

    private static final class Endpoint implements AutoCloseable {
        private final HttpServer server;
        private final LinkedBlockingQueue<Request> requests = new LinkedBlockingQueue<>();
        private final AtomicInteger failures = new AtomicInteger();

        Endpoint() throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/v1/", exchange -> {
                try (exchange) {
                    var headers = exchange.getRequestHeaders();
                    requests.add(new Request(exchange.getRequestURI().getPath(), headers.getFirst("Content-Type"),
                            headers.getFirst("Authorization"), headers.getFirst("X-Route"),
                            new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
                    boolean fail = failures.getAndDecrement() > 0;
                    byte[] bytes = (fail ? "{\"error\":{\"message\":\"retry\"}}" : """
                            {"created":1,"output_format":"jpeg","data":[
                            {"url":"https://images.example/tree","revised_prompt":"revised tree"},
                            {"b64_json":"c2Vjb25k"}],"usage":{"input_tokens":15,"output_tokens":80,"total_tokens":95,
                            "input_tokens_details":{"text_tokens":5,"image_tokens":10},
                            "output_tokens_details":{"image_tokens":80}}}
                            """).getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(fail ? 500 : 200, bytes.length);
                    exchange.getResponseBody().write(bytes);
                }
            });
            server.start();
        }

        String baseUrl() { return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/"; }
        Request take() throws InterruptedException {
            Request request = requests.poll(5, TimeUnit.SECONDS);
            assertNotNull(request);
            return request;
        }
        public void close() { server.stop(0); }
    }
}
