package com.summit.harness.springbootautoconfigure.config;

import anthropic.AnthropicChatModel;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.summit.core.agent.Image;
import com.summit.core.conf.ModelConfig;
import com.summit.core.conversation.api.ChatRequestEntity;
import com.summit.core.conversation.api.ChatResponseEntity;
import com.summit.core.conversation.api.ChatResponseEntity.FinishReason;
import com.summit.core.conversation.message.Message;
import com.summit.core.conversation.message.SystemMessageEntity;
import com.summit.core.conversation.message.ToolMessageEntity;
import com.summit.core.conversation.message.UserMessageEntity;
import com.summit.core.exception.RetriableModelException;
import com.summit.core.model.streaming.StreamingChatResponseHandler;
import com.summit.core.model.streaming.StreamingHandler;
import com.summit.core.tool.ToolDefinition;
import com.summit.core.tool.ToolExecuteResult;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class AnthropicChatWireTest {
    @Test
    void streamsTextThinkingAndFragmentedToolsWithCumulativeUsage() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        var requests = new LinkedBlockingQueue<JsonNode>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/messages", exchange -> {
            try (exchange) {
                requests.add(mapper.readTree(exchange.getRequestBody()));
                byte[] body = """
                        event: message_start
                        data: {"type":"message_start","message":{"id":"msg_1","model":"claude-test","content":[],"usage":{"input_tokens":10,"cache_read_input_tokens":4,"output_tokens":1}}}

                        : heartbeat
                        event: ping
                        data: {"type":"ping"}

                        data: {"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":""}}

                        data: {"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"plan"}}

                        data: {"type":"content_block_delta","index":0,"delta":{"type":"signature_delta","signature":"signed"}}

                        data: {"type":"content_block_stop","index":0}

                        data: {"type":"content_block_start","index":1,"content_block":{"type":"redacted_thinking","data":"opaque"}}

                        data: {"type":"content_block_stop","index":1}

                        data: {"type":"content_block_start","index":2,"content_block":{"type":"text","text":""}}

                        data: {"type":"content_block_delta","index":2,
                        data: "delta":{"type":"text_delta","text":"Searching"}}

                        data: {"type":"content_block_stop","index":2}

                        data: {"type":"content_block_start","index":3,"content_block":{"type":"tool_use","id":"toolu_1","name":"lookup","input":{}}}

                        data: {"type":"content_block_delta","index":3,"delta":{"type":"input_json_delta","partial_json":"{"}}

                        data: {"type":"content_block_delta","index":3,"delta":{"type":"input_json_delta","partial_json":"\\"q\\":1}"}}

                        data: {"type":"content_block_stop","index":3}

                        data: {"type":"message_delta","delta":{"stop_reason":null},"usage":{"output_tokens":4}}

                        data: {"type":"message_delta","delta":{"stop_reason":"tool_use"},"usage":{"output_tokens":7}}

                        data: {"type":"message_stop"}

                        """.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
        });
        server.start();
        try (HttpClient client = HttpClient.newHttpClient()) {
            var config = config(server).returnThinking(true)
                    .anthropicOptions(ModelConfig.AnthropicOptions.builder().thinkingType("adaptive").build()).build();
            var model = new AnthropicChatModel(config, client, mapper);
            var capture = new StreamCapture();
            var user = UserMessageEntity.from("Look this up");
            model.chat(ChatRequestEntity.builder().messages(List.of(user)).build(), capture);
            var response = capture.result.get(5, TimeUnit.SECONDS);
            assertEquals(List.of("Searching"), capture.text);
            assertEquals(List.of("plan"), capture.thinking);
            assertEquals(List.of("0:toolu_1:lookup:null", "0:null:null:{", "0:null:null:\"q\":1}"), capture.tools);
            assertEquals("plan", response.getAiMessageEntity().getThinking());
            assertEquals("Searching", response.getAiMessageEntity().getText());
            assertEquals("{\"q\":1}", response.getAiMessageEntity().getToolCalls().getFirst().arguments());
            assertEquals(FinishReason.TOOL_EXECUTION, response.getMeta().getFinishReason());
            assertEquals(21, response.getTokenUsage().getTotalTokens());
            assertEquals(4, response.getTokenUsage().getInputTokens().getCacheHitTokens());
            JsonNode first = requests.poll(5, TimeUnit.SECONDS);
            assertNotNull(first);
            assertTrue(first.path("stream").asBoolean());
            assertEquals("adaptive", first.at("/thinking/type").asText());

            var toolResult = ToolMessageEntity.builder().id("toolu_1").toolOutput("found").build();
            var replay = new StreamCapture();
            new AnthropicChatModel(config.toBuilder().returnThinking(false).build(), client, mapper)
                    .chat(ChatRequestEntity.builder().messages(List.of(user, response.getAiMessageEntity(), toolResult)).build(), replay);
            assertNull(replay.result.get(5, TimeUnit.SECONDS).getAiMessageEntity().getThinking());
            assertTrue(replay.thinking.isEmpty());
            JsonNode second = requests.poll(5, TimeUnit.SECONDS);
            assertNotNull(second);
            assertEquals("signed", second.at("/messages/1/content/0/signature").asText());
            assertEquals("opaque", second.at("/messages/1/content/1/data").asText());
            assertEquals("toolu_1", second.at("/messages/1/content/3/id").asText());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void reportsStreamErrorsAndPrematureEofAsRetryable() throws Exception {
        var responses = new LinkedBlockingQueue<>(List.of(
                "data: {\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\"}}\n\n",
                "data: {\"type\":\"message_start\",\"message\":{\"content\":[]}}\n\n"));
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/messages", exchange -> {
            try (exchange) {
                byte[] body = responses.remove().getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
        });
        server.start();
        try (HttpClient client = HttpClient.newHttpClient()) {
            var model = new AnthropicChatModel(config(server).build(), client, new ObjectMapper());
            for (String message : List.of("overloaded_error", "before message_stop")) {
                var capture = new StreamCapture();
                model.chat(ChatRequestEntity.builder().messages(List.of(UserMessageEntity.from("hello"))).build(), capture);
                var failure = assertThrows(ExecutionException.class, () -> capture.result.get(5, TimeUnit.SECONDS));
                assertInstanceOf(RetriableModelException.class, failure.getCause());
                assertTrue(failure.getCause().getMessage().contains(message));
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    void cancelsWhileWaitingForTheFirstDelta() throws Exception {
        CountDownLatch headersSent = new CountDownLatch(1);
        CountDownLatch releaseServer = new CountDownLatch(1);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/messages", exchange -> {
            try (exchange) {
                exchange.getRequestBody().readAllBytes();
                exchange.sendResponseHeaders(200, 0);
                headersSent.countDown();
                releaseServer.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            }
        });
        server.start();
        try (HttpClient client = HttpClient.newHttpClient()) {
            var capture = new StreamCapture();
            new AnthropicChatModel(config(server).build(), client, new ObjectMapper())
                    .chat(ChatRequestEntity.builder().messages(List.of(UserMessageEntity.from("hello"))).build(), capture);
            assertNotNull(capture.handle);
            assertTrue(headersSent.await(5, TimeUnit.SECONDS));
            capture.handle.cancel();
            assertTrue(capture.handle.isCancelled());
            assertFalse(capture.result.isDone());
        } finally {
            releaseServer.countDown();
            server.stop(0);
        }
    }

    private static ModelConfig.ModelConfigBuilder config(HttpServer server) {
        return ModelConfig.builder().modelName("claude-test").apiKey("test-key").maxTokens(128)
                .timeout(Duration.ofSeconds(5))
                .baseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/messages");
    }

    private static final class StreamCapture implements StreamingChatResponseHandler {
        private final CompletableFuture<ChatResponseEntity> result = new CompletableFuture<>();
        private final List<String> text = new ArrayList<>();
        private final List<String> thinking = new ArrayList<>();
        private final List<String> tools = new ArrayList<>();
        private StreamingHandler handle;

        public void onStreamStart(StreamingHandler handle) { this.handle = handle; }
        public void onPartialResponse(String part, StreamingHandler handle) { text.add(part); }
        public void onPartialThinking(String part, StreamingHandler handle) { thinking.add(part); }
        public void onPartialToolCall(int index, String id, String name, String arguments, StreamingHandler handle) {
            tools.add(index + ":" + id + ":" + name + ":" + arguments);
        }
        public void onFinalResponse(ChatResponseEntity response) { result.complete(response); }
        public void onError(Throwable error) { result.completeExceptionally(error); }
    }

    @Test
    void replaysNativeToolBlocksAndAcceptsAToolOnlyResponse() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        var requests = new LinkedBlockingQueue<JsonNode>();
        var versions = new LinkedBlockingQueue<String>();
        var responses = new LinkedBlockingQueue<>(List.of("""
                {"id":"msg_1","type":"message","role":"assistant","model":"claude-test",
                 "content":[{"type":"tool_use","id":"toolu_1","name":"lookup","input":{"q":{"value":1}}},
                            {"type":"tool_use","id":"toolu_2","name":"lookup","input":{}}],
                 "stop_reason":"tool_use","stop_sequence":null,
                 "usage":{"input_tokens":10,"output_tokens":2,"cache_creation_input_tokens":3,"cache_read_input_tokens":4}}
                """, """
                {"id":"msg_2","model":"claude-test","stop_reason":"end_turn",
                 "content":[{"type":"text","text":"all ","citations":null},{"type":"text","text":"done"}],
                 "usage":{"input_tokens":20,"output_tokens":3}}
                """));
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/messages", exchange -> {
            try (exchange) {
                requests.add(mapper.readTree(exchange.getRequestBody()));
                versions.add(exchange.getRequestHeaders().getFirst("anthropic-version"));
                byte[] body = responses.remove().getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
        });
        server.start();
        try (HttpClient client = HttpClient.newHttpClient()) {
            var config = ModelConfig.builder().modelName("claude-test").apiKey("test-key").maxTokens(128)
                    .timeout(Duration.ofSeconds(5)).stream(true)
                    .baseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/messages").build();
            var model = new AnthropicChatModel(config, client, mapper);
            List<Message> history = new ArrayList<>(List.of(SystemMessageEntity.builder().text("Use tools").build(),
                    UserMessageEntity.from("Look this up", List.of(Image.from("cG5n"),
                            Image.from(URI.create("https://example.com/reference.png"))))));
            var tool = ToolDefinition.builder().id("lookup").name("lookup").maxOutput(1024).timeout(5L)
                    .executor(ignored -> ToolExecuteResult.success("ok"))
                    .parametersJsonSchema("{\"type\":\"object\",\"properties\":{\"q\":{\"type\":\"object\"}}}").build();
            var first = model.chat(ChatRequestEntity.builder().messages(history).tools(List.of(tool)).build());
            assertEquals("2023-06-01", versions.poll(5, TimeUnit.SECONDS));
            JsonNode sent = requests.poll(5, TimeUnit.SECONDS);
            assertNotNull(sent);
            assertEquals("Use tools", sent.at("/system/0/text").asText());
            assertFalse(sent.path("stream").asBoolean());
            assertEquals(128, sent.path("max_tokens").asInt());
            assertEquals("user", sent.at("/messages/0/role").asText());
            assertEquals("Look this up", sent.at("/messages/0/content/0/text").asText());
            assertEquals("image/png", sent.at("/messages/0/content/1/source/media_type").asText());
            assertEquals("https://example.com/reference.png", sent.at("/messages/0/content/2/source/url").asText());
            assertTrue(sent.at("/tools/0/input_schema").isObject());
            assertNull(first.getAiMessageEntity().getText());
            assertEquals(FinishReason.TOOL_EXECUTION, first.getMeta().getFinishReason());
            assertEquals(19, first.getTokenUsage().getTotalTokens());
            assertEquals(4, first.getTokenUsage().getInputTokens().getCacheHitTokens());
            var calls = first.getAiMessageEntity().getToolCalls();
            assertEquals(2, calls.size());
            assertEquals(1, mapper.readTree(calls.getFirst().arguments()).at("/q/value").asInt());
            assertEquals(1, calls.getLast().requestIndex());

            history.add(first.getAiMessageEntity());
            history.add(ToolMessageEntity.builder().id("toolu_1").toolOutput("found").build());
            history.add(ToolMessageEntity.builder().id("toolu_2").toolOutput("failed").isError(true).build());
            var second = model.chat(ChatRequestEntity.builder().messages(history).tools(List.of(tool)).build());
            JsonNode replay = requests.poll(5, TimeUnit.SECONDS);
            assertNotNull(replay);
            assertEquals(3, replay.path("messages").size());
            assertEquals("tool_use", replay.at("/messages/1/content/0/type").asText());
            assertEquals(1, replay.at("/messages/1/content/0/input/q/value").asInt());
            assertEquals("tool_result", replay.at("/messages/2/content/0/type").asText());
            assertEquals("toolu_1", replay.at("/messages/2/content/0/tool_use_id").asText());
            assertTrue(replay.at("/messages/2/content/1/is_error").asBoolean());
            assertEquals("all done", second.getAiMessageEntity().getText());
            assertEquals(FinishReason.STOP, second.getMeta().getFinishReason());
        } finally {
            server.stop(0);
        }
    }
}
