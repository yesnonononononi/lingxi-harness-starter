package com.summit.adapter.langchain4j.model.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.summit.core.conf.ModelConfig;
import com.summit.core.conversation.api.ChatRequestEntity;
import com.summit.core.conversation.api.ChatResponseEntity;
import com.summit.core.conversation.message.Message;
import com.summit.core.conversation.message.SystemMessageEntity;
import com.summit.core.conversation.message.ToolMessageEntity;
import com.summit.core.conversation.message.UserMessageEntity;
import com.summit.core.model.streaming.StreamingChatResponseHandler;
import com.summit.core.model.streaming.StreamingHandler;
import com.summit.core.tool.ToolDefinition;
import com.summit.core.tool.ToolExecuteResult;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Witnesses real SDK serialization and response parsing through a local HTTP endpoint. */
class ProviderWireContractTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void anthropicEffortAndSignedThinkingSurviveTwoModelRounds(boolean streaming) throws Exception {
        try (Endpoint endpoint = new Endpoint()) {
            ModelConfig config = config(endpoint, "claude-sonnet-4-6").reasoningEffort("high").build();
            ChatResponseEntity first = call(config, streaming, List.of(UserMessageEntity.from("question")), true);
            JsonNode firstRequest = endpoint.requests.poll(10, TimeUnit.SECONDS);
            assertNotNull(firstRequest);
            assertEquals("adaptive", firstRequest.path("thinking").path("type").asText());
            assertFalse(firstRequest.path("thinking").has("budget_tokens"));
            assertEquals("high", firstRequest.path("output_config").path("effort").asText());
            assertFalse(firstRequest.has("reasoning_effort"));
            assertEquals(streaming, firstRequest.path("stream").asBoolean());
            assertEquals("reason", first.getAiMessageEntity().getThinking());
            assertEquals("signed", first.getAiMessageEntity().getAttributes().get("thinking_signature"));
            assertEquals(List.of("opaque"), first.getAiMessageEntity().getAttributes().get("redacted_thinking"));
            assertEquals(210, first.getTokenUsage().getTotalTokens());
            assertEquals(180, first.getTokenUsage().getInputTokens().getInputTokens());
            assertEquals(60, first.getTokenUsage().getInputTokens().getCacheHitTokens());
            assertEquals("call-1", first.getAiMessageEntity().getToolCalls().getFirst().id());
            call(config, streaming, List.of(UserMessageEntity.from("question"), first.getAiMessageEntity(),
                    ToolMessageEntity.builder().id("call-1").name("lookup").toolOutput("result").build()), true);
            JsonNode next = endpoint.requests.poll(10, TimeUnit.SECONDS);
            assertNotNull(next);
            JsonNode content = next.path("messages").get(1).path("content");
            assertEquals("thinking", content.get(0).path("type").asText());
            assertEquals("signed", content.get(0).path("signature").asText());
            assertEquals("redacted_thinking", content.get(1).path("type").asText());
            assertEquals("opaque", content.get(1).path("data").asText());
            assertEquals("tool_use", content.get(2).path("type").asText());
            assertEquals("call-1", next.path("messages").get(2).path("content").get(0).path("tool_use_id").asText());
            assertEquals("result", next.path("messages").get(2).path("content").get(0).path("content").asText());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void openAiKeepsItsNativeReasoningEffort(boolean streaming) throws Exception {
        try (Endpoint endpoint = new Endpoint()) {
            call(config(endpoint, "gpt-test").reasoningEffort("xhigh").build(), streaming,
                    List.of(UserMessageEntity.from("question")), false);
            JsonNode request = endpoint.requests.poll(10, TimeUnit.SECONDS);
            assertNotNull(request);
            assertEquals("xhigh", request.path("reasoning_effort").asText());
            assertFalse(request.has("thinking"));
            assertFalse(request.has("output_config"));
        }
    }

    private ModelConfig.ModelConfigBuilder config(Endpoint endpoint, String model) {
        return ModelConfig.builder().baseUrl(endpoint.baseUrl()).apiKey("test-key").modelName(model)
                .timeout(Duration.ofSeconds(10)).maxTokens(4096).sendThinking(true).returnThinking(true);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void manualThinkingUsesBudgetBelowTheOutputLimit(boolean streaming) throws Exception {
        try (Endpoint endpoint = new Endpoint()) {
            call(config(endpoint, "claude-sonnet-4-5").reasoningEffort("high").build(), streaming,
                    List.of(UserMessageEntity.from("question")), true);
            JsonNode request = endpoint.requests.poll(10, TimeUnit.SECONDS);
            assertNotNull(request);
            assertEquals("enabled", request.path("thinking").path("type").asText());
            assertEquals(4095, request.path("thinking").path("budget_tokens").asInt());
            assertEquals(4096, request.path("max_tokens").asInt());
            assertFalse(request.has("output_config"));
        }
    }

    @Test
    void compactProviderUsesNativeAnthropicWithThinkingDisabled() throws Exception {
        try (Endpoint endpoint = new Endpoint()) {
            new AnthropicCompactContextModelProvider().create(config(endpoint, "claude-sonnet-4-6")
                    .reasoningEffort("none").build()).chat(ChatRequestEntity.builder()
                    .messages(List.of(UserMessageEntity.from("summarize"))).build());
            JsonNode request = endpoint.requests.poll(10, TimeUnit.SECONDS);
            assertNotNull(request);
            assertEquals("disabled", request.path("thinking").path("type").asText());
            assertFalse(request.has("output_config"));
        }
    }

    /**
     * Anthropic reports zero cache reads unless the request marks the reusable prefix with
     * {@code cache_control}; the flag has to reach the wire, not just sit in the config.
     */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void anthropicMarksReusablePrefixOnlyWhenPromptCacheEnabled(boolean enabled) throws Exception {
        try (Endpoint endpoint = new Endpoint()) {
            ModelConfig config = config(endpoint, "claude-sonnet-4-6").promptCache(enabled).build();
            ToolDefinition<?> lookup = ToolDefinition.builder()
                    .id("lookup").name("lookup").description("Lookup something")
                    .maxOutput(100).timeout(5L)
                    .executor(execution -> ToolExecuteResult.success("ok"))
                    .build();
            ChatRequestEntity request = ChatRequestEntity.builder()
                    .messages(List.of(SystemMessageEntity.builder().text("system prompt").build(),
                            UserMessageEntity.from("question")))
                    .tools(List.of(lookup))
                    .build();
            new AnthropicChatModelProvider().create(config).chat(request);

            JsonNode body = endpoint.requests.poll(10, TimeUnit.SECONDS);
            assertNotNull(body);
            String systemCache = body.path("system").get(0).path("cache_control").path("type").asText("");
            String toolCache = body.path("tools").get(0).path("cache_control").path("type").asText("");
            assertEquals(enabled, "ephemeral".equals(systemCache), "system prompt cache marker");
            assertEquals(enabled, "ephemeral".equals(toolCache), "tool definitions cache marker");
        }
    }

    private ChatResponseEntity call(ModelConfig config, boolean streaming, List<Message> messages,
                                    boolean anthropic) throws Exception {
        ChatRequestEntity request = ChatRequestEntity.builder().messages(messages).build();
        if (!streaming) {
            return (anthropic ? new AnthropicChatModelProvider().create(config)
                    : new OpenAiChatModelProvider().create(config)).chat(request);
        }
        CompletableFuture<ChatResponseEntity> completed = new CompletableFuture<>();
        (anthropic ? new AnthropicStreamingModelProvider().create(config)
                : new OpenAiStreamingModelProvider().create(config)).chat(request, new StreamingChatResponseHandler() {
            public void onPartialResponse(String text, StreamingHandler handle) {}
            public void onPartialThinking(String text, StreamingHandler handle) {}
            public void onFinalResponse(ChatResponseEntity response) { completed.complete(response); }
            public void onError(Throwable error) { completed.completeExceptionally(error); }
        });
        return completed.get(10, TimeUnit.SECONDS);
    }

    private static final class Endpoint implements AutoCloseable {
        private final HttpServer server;
        private final LinkedBlockingQueue<JsonNode> requests = new LinkedBlockingQueue<>();
        private Endpoint() throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/v1/", exchange -> {
                try (exchange) {
                    JsonNode body = new ObjectMapper().readTree(exchange.getRequestBody());
                    requests.add(body);
                    boolean streaming = body.path("stream").asBoolean();
                    boolean anthropic = exchange.getRequestURI().getPath().endsWith("messages");
                    String response = anthropic ? (streaming ? anthropicStream() : anthropicResponse())
                            : (streaming ? openAiStream() : openAiResponse());
                    byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", streaming ? "text/event-stream" : "application/json");
                    exchange.sendResponseHeaders(200, bytes.length);
                    exchange.getResponseBody().write(bytes);
                }
            });
            server.start();
        }
        private String baseUrl() { return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/"; }
        public void close() { server.stop(0); }

        private static String anthropicResponse() {
            return """
                    {"id":"msg-1","type":"message","role":"assistant","model":"claude-sonnet-4-6",
                    "content":[{"type":"thinking","thinking":"reason","signature":"signed"},
                    {"type":"redacted_thinking","data":"opaque"},
                    {"type":"tool_use","id":"call-1","name":"lookup","input":{}}],
                    "stop_reason":"tool_use","usage":{"input_tokens":100,"output_tokens":30,
                    "cache_creation_input_tokens":20,"cache_read_input_tokens":60}}
                    """;
        }
        private static String anthropicStream() {
            return event("message_start", """
                    {"type":"message_start","message":{"id":"msg-1","type":"message","role":"assistant",
                    "model":"claude-sonnet-4-6","content":[],"usage":{"input_tokens":100,"output_tokens":0,
                    "cache_creation_input_tokens":20,"cache_read_input_tokens":60}}}
                    """) + event("content_block_start", """
                    {"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":""}}
                    """) + event("content_block_delta", """
                    {"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"reason"}}
                    """) + event("content_block_delta", """
                    {"type":"content_block_delta","index":0,"delta":{"type":"signature_delta","signature":"signed"}}
                    """) + event("content_block_stop", "{\"type\":\"content_block_stop\",\"index\":0}")
                    + event("content_block_start", """
                    {"type":"content_block_start","index":1,"content_block":{"type":"redacted_thinking","data":"opaque"}}
                    """) + event("content_block_stop", "{\"type\":\"content_block_stop\",\"index\":1}")
                    + event("content_block_start", """
                    {"type":"content_block_start","index":2,"content_block":{"type":"tool_use","id":"call-1","name":"lookup","input":{}}}
                    """) + event("content_block_delta", """
                    {"type":"content_block_delta","index":2,"delta":{"type":"input_json_delta","partial_json":"{}"}}
                    """) + event("content_block_stop", "{\"type\":\"content_block_stop\",\"index\":2}")
                    + event("message_delta", """
                    {"type":"message_delta","delta":{"stop_reason":"tool_use"},"usage":{"output_tokens":30}}
                    """) + event("message_stop", "{\"type\":\"message_stop\"}");
        }
        private static String event(String name, String json) {
            return "event: " + name + "\ndata: " + json.strip().replace("\n", "") + "\n\n";
        }
        private static String openAiResponse() {
            return """
                    {"id":"msg-1","model":"gpt-test","choices":[{"index":0,"message":{"role":"assistant","content":"answer"},"finish_reason":"stop"}]}
                    """;
        }
        private static String openAiStream() {
            return event("", """
                    {"id":"msg-1","model":"gpt-test","choices":[{"index":0,"delta":{"role":"assistant","content":"answer"}}]}
                    """) + event("", """
                    {"id":"msg-1","model":"gpt-test","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}
                    """) + "data: [DONE]\n\n";
        }
    }
}
