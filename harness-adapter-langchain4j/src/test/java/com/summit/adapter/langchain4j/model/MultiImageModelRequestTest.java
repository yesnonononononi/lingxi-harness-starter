package com.summit.adapter.langchain4j.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.summit.core.agent.Image;
import com.summit.core.conversation.api.ChatRequestEntity;
import com.summit.core.conversation.api.ChatResponseEntity;
import com.summit.core.conversation.message.UserMessageEntity;
import com.summit.core.model.streaming.StreamingChatResponseHandler;
import com.summit.core.model.streaming.StreamingHandler;
import com.sun.net.httpserver.HttpServer;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The HTTP endpoint witnesses the serialized model request, beyond message conversion. */
class MultiImageModelRequestTest {
    @Test
    void synchronousModelReceivesAllImagesWithTheirEncodingAndOrder() throws Exception {
        try (ModelEndpoint endpoint = new ModelEndpoint()) {
            ChatModelAdapter adapter = new ChatModelAdapter(OpenAiChatModel.builder()
                    .baseUrl(endpoint.baseUrl()).apiKey("test-key").modelName("test-model")
                    .timeout(Duration.ofSeconds(10)).maxRetries(0).build());
            ChatResponseEntity response = adapter.chat(request());
            assertEquals("received all images", response.getAiMessageEntity().text());
            assertImages(endpoint.received.get(10, TimeUnit.SECONDS), false);
        }
    }

    @Test
    void streamingModelReceivesAllImagesWithTheirEncodingAndOrder() throws Exception {
        try (ModelEndpoint endpoint = new ModelEndpoint()) {
            StreamingChatModelAdapter adapter = new StreamingChatModelAdapter(OpenAiStreamingChatModel.builder()
                    .baseUrl(endpoint.baseUrl()).apiKey("test-key").modelName("test-model")
                    .timeout(Duration.ofSeconds(10)).build());
            CompletableFuture<ChatResponseEntity> completed = new CompletableFuture<>();
            adapter.chat(request(), new StreamingChatResponseHandler() {
                @Override
                public void onPartialResponse(String text, StreamingHandler handler) {}
                @Override
                public void onPartialThinking(String thinking, StreamingHandler handler) {}
                @Override
                public void onFinalResponse(ChatResponseEntity response) { completed.complete(response); }
                @Override
                public void onError(Throwable error) { completed.completeExceptionally(error); }
            });
            assertEquals("received all images", completed.get(10, TimeUnit.SECONDS).getAiMessageEntity().text());
            assertImages(endpoint.received.get(10, TimeUnit.SECONDS), true);
        }
    }

    private ChatRequestEntity request() {
        UserMessageEntity message = UserMessageEntity.from("compare all three", List.of(
                Image.from("cG5n", "image/png"),
                Image.from(URI.create("https://example.com/second.jpg")),
                Image.from("anBlZw==", "image/jpeg")));
        return ChatRequestEntity.builder().messages(List.of(message)).build();
    }

    private void assertImages(JsonNode request, boolean streaming) {
        assertEquals("test-model", request.path("model").asText());
        assertEquals(streaming, request.path("stream").asBoolean());
        JsonNode messages = request.path("messages");
        assertEquals(1, messages.size());
        assertEquals("user", messages.get(0).path("role").asText());
        JsonNode contents = messages.get(0).path("content");
        assertEquals(4, contents.size());
        assertEquals("text", contents.get(0).path("type").asText());
        assertEquals("compare all three", contents.get(0).path("text").asText());
        List<String> urls = List.of("data:image/png;base64,cG5n", "https://example.com/second.jpg", "data:image/jpeg;base64,anBlZw==");
        for (int index = 0; index < urls.size(); index++) {
            assertEquals("image_url", contents.get(index + 1).path("type").asText());
            assertEquals(urls.get(index), contents.get(index + 1).path("image_url").path("url").asText());
        }
        assertEquals("auto", contents.get(2).path("image_url").path("detail").asText());
    }

    private static final class ModelEndpoint implements AutoCloseable {
        private final HttpServer server;
        private final CompletableFuture<JsonNode> received = new CompletableFuture<>();

        private ModelEndpoint() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/v1/chat/completions", exchange -> {
                try (exchange) {
                    JsonNode body = new ObjectMapper().readTree(exchange.getRequestBody());
                    received.complete(body);
                    boolean streaming = body.path("stream").asBoolean();
                    String response = streaming
                            ? "data: {\"id\":\"test-id\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"test-model\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"received all images\"}}]}\n\n"
                            + "data: {\"id\":\"test-id\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"test-model\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"
                            : "{\"id\":\"test-id\",\"object\":\"chat.completion\",\"created\":1,\"model\":\"test-model\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"received all images\"},\"finish_reason\":\"stop\"}]}";
                    byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", streaming ? "text/event-stream" : "application/json");
                    exchange.sendResponseHeaders(200, bytes.length);
                    exchange.getResponseBody().write(bytes);
                } catch (Throwable error) {
                    received.completeExceptionally(error);
                }
            });
            server.start();
        }

        private String baseUrl() { return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1"; }
        @Override
        public void close() { server.stop(0); }
    }
}
