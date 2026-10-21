package com.summit.adapter.langchain4j.model.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.summit.core.agent.Image;
import com.summit.core.conf.ModelConfig;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Uses the real SDK against a local server, including multipart uploads and retries. */
class ImageProviderWireContractTest {
    private final ObjectMapper mapper = new ObjectMapper();

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
            if (retries == 0) assertThrows(RuntimeException.class, () -> model.generate("draw"));
            else assertEquals(1, model.generate("draw").getMeta().getImages().size());
            assertEquals(retries + 1, endpoint.requests.size());
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
