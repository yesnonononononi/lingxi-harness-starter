package com.summit.runtime.compact;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.summit.adapter.langchain4j.model.provider.AnthropicChatModelProvider;
import com.summit.adapter.langchain4j.model.provider.OpenAiChatModelProvider;
import com.summit.core.agent.AgentRequest;
import com.summit.core.agent.Execution;
import com.summit.core.compact.ContextCompactRequest;
import com.summit.core.conf.ModelConfig;
import com.summit.core.conversation.api.ChatRequestEntity;
import com.summit.core.conversation.api.ToolCallRequest;
import com.summit.core.conversation.event.RuntimeEventPublisher;
import com.summit.core.conversation.message.AiMessageEntity;
import com.summit.core.conversation.message.Message;
import com.summit.core.conversation.message.SystemMessageEntity;
import com.summit.core.conversation.message.ToolMessageEntity;
import com.summit.core.conversation.message.UserMessageEntity;
import com.summit.core.runtime.loop.ContextUsageReporter;
import com.summit.runtime.agent.AgentConfig;
import com.summit.runtime.conversation.DefaultTokenizer;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks pending prefixes, skeleton history, and text-only history through both real adapters.
 *
 * <p>The providers ({@link AnthropicChatModelProvider},
 * {@link OpenAiChatModelProvider}) talking to a local endpoint, so the assertions read the exact JSON
 * both SDKs put on the wire. What this cannot prove offline is the remote server's own validation: no
 * test in this repository can call Anthropic or OpenAI. The check therefore pins the wire shape — the
 * part a server actually judges — instead of pretending to have reached it.</p>
 */
class PrunedContextWireAcceptanceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_TOKENS = 10_000;

    /**
     * §8.4 ①: an active group is kept verbatim, and the resulting request is identical to the one the
     * unpruned context already produces today — pruning introduces no new wire shape for it.
     */
    @Test
    void frozenActiveGroupKeepsThinkingAndTheRequestStaysUnchanged() throws Exception {
        AiMessageEntity active = AiMessageEntity.builder()
                .thinking("reasoning that has not finished")
                .attributes(Map.of("thinking_signature", "signed"))
                .toolCalls(List.of(new ToolCallRequest("call-1", "lookup", 0, "{}")))
                .build();
        List<Message> original = List.of(
                SystemMessageEntity.builder().text("system").build(),
                UserMessageEntity.from("question"),
                active);

        List<Message> pruned = prune(original);
        assertEquals(original.size(), pruned.size(),
                "an active group is frozen: pruning keeps it and the rest of the session");
        AiMessageEntity kept = (AiMessageEntity) pruned.get(2);
        assertEquals("reasoning that has not finished", kept.getThinking(),
                "a frozen group keeps its thinking");

        try (Endpoint endpoint = new Endpoint()) {
            JsonNode before = wire(endpoint, original, true);
            JsonNode after = wire(endpoint, pruned, true);
            assertEquals(before, after,
                    "pruning an active group must not change one byte of the Anthropic request: "
                            + before + " vs " + after);

            JsonNode thinking = anthropicBlock(after, "thinking");
            assertNotNull(thinking, "the thinking block survives: " + after);
            assertEquals("signed", thinking.path("signature").asText());
            JsonNode toolUse = anthropicBlock(after, "tool_use");
            assertNotNull(toolUse, "the pending tool call is still there: " + after);
            assertEquals("call-1", toolUse.path("id").asText());

            JsonNode openAi = wire(endpoint, pruned, false);
            JsonNode assistant = openAiToolCallAssistant(openAi);
            assertNotNull(assistant.path("tool_calls").get(0),
                    "OpenAI keeps the tool call: " + openAi);
            assertEquals("call-1", assistant.path("tool_calls").get(0).path("id").asText(),
                    "OpenAI keeps the tool call: " + openAi);
            assertFalse(openAi.toString().contains("\"thinking_signature\""),
                    "OpenAI has no thinking signature: " + openAi);
        }
    }

    /** §8.4 ②: a historical segment without thinking still pairs tool_use with tool_result. */
    @Test
    void historicalSegmentWithoutThinkingKeepsToolPairing() throws Exception {
        AiMessageEntity historical = AiMessageEntity.builder()
                .thinking("old reasoning")
                .attributes(Map.of("thinking_signature", "signed"))
                .toolCalls(List.of(new ToolCallRequest("call-1", "lookup", 0, "{}")))
                .build();
        List<Message> original = List.of(
                SystemMessageEntity.builder().text("system").build(),
                UserMessageEntity.from("question"),
                historical,
                ToolMessageEntity.builder().id("call-1").name("lookup")
                        .toolOutput("x".repeat(3_000)).exitCode(1).build(),
                AiMessageEntity.builder().text("answer").build());

        List<Message> pruned = prune(original);
        assertEquals(5, pruned.size(), "nothing is dropped at this budget: " + pruned);
        AiMessageEntity rewritten = (AiMessageEntity) pruned.get(2);
        assertNull(rewritten.getThinking(), "a historical segment loses its thinking");
        assertEquals(1, rewritten.getToolCalls().size(), "but keeps its tool call");

        try (Endpoint endpoint = new Endpoint()) {
            JsonNode anthropic = wire(endpoint, pruned, true);
            assertNull(anthropicBlock(anthropic, "thinking"),
                    "no thinking block precedes the tool_use: " + anthropic);
            JsonNode toolUse = anthropicBlock(anthropic, "tool_use");
            assertNotNull(toolUse, "the tool call is still there: " + anthropic);
            assertEquals("call-1", toolUse.path("id").asText());
            JsonNode toolResult = anthropicBlock(anthropic, "tool_result");
            assertNotNull(toolResult, "the result is still there: " + anthropic);
            assertEquals("call-1", toolResult.path("tool_use_id").asText(),
                    "the result still answers the call it belongs to: " + anthropic);

            JsonNode openAi = wire(endpoint, pruned, false);
            assertNotNull(openAiToolCallAssistant(openAi).path("tool_calls").get(0),
                    "OpenAI keeps the tool call: " + openAi);
            assertEquals("call-1", openAiToolCallAssistant(openAi).path("tool_calls").get(0).path("id").asText(),
                    "OpenAI keeps the tool call: " + openAi);
            JsonNode openAiTool = openAiMessage(openAi, "tool");
            assertNotNull(openAiTool, "OpenAI keeps the tool result: " + openAi);
            assertEquals("call-1", openAiTool.path("tool_call_id").asText());
        }
    }

    /** §8.4 ③: a plain-text assistant skeleton is an ordinary assistant turn in both protocols. */
    @Test
    void plainTextAssistantSkeletonIsAccepted() throws Exception {
        List<Message> original = List.of(
                SystemMessageEntity.builder().text("system").build(),
                UserMessageEntity.from("question"),
                AiMessageEntity.builder().text("a plain answer").build(),
                UserMessageEntity.from("follow-up"));

        // The plain-text segment may be dropped or kept as a skeleton; either way it must never carry
        // a tool structure a protocol would have to pair.
        List<Message> pruned = prune(original);
        assertTrue(pruned.stream().filter(AiMessageEntity.class::isInstance)
                        .allMatch(message -> ((AiMessageEntity) message).getToolCalls() == null
                                || ((AiMessageEntity) message).getToolCalls().isEmpty()),
                "a plain-text skeleton has no tool calls");

        try (Endpoint endpoint = new Endpoint()) {
            JsonNode anthropic = wire(endpoint, pruned, true);
            JsonNode text = anthropicBlock(anthropic, "text");
            assertNotNull(text, "the assistant text is still there: " + anthropic);
            assertNull(anthropicBlock(anthropic, "tool_use"),
                    "a plain-text skeleton has no tool_use: " + anthropic);

            JsonNode openAi = wire(endpoint, pruned, false);
            JsonNode assistant = openAiAssistant(openAi);
            assertEquals("assistant", assistant.path("role").asText());
            assertFalse(assistant.has("tool_calls"),
                    "a plain-text skeleton must not look like a tool turn: " + openAi);
        }
    }

    @Test
    void textOnlyHistoryAndTheLatestToolPairReachBothProviders() throws Exception {
        List<Message> original = List.of(
                SystemMessageEntity.builder().text("system").build(),
                UserMessageEntity.from("question"),
                AiMessageEntity.builder().text("earlier finding").toolCalls(List.of(
                        new ToolCallRequest("discarded-call", "read", 0,
                                "{\"content\":\"" + "x".repeat(40_000) + "\"}"))).build(),
                ToolMessageEntity.builder().id("discarded-call").name("read").toolOutput("old output").build(),
                AiMessageEntity.builder().text("latest working group").toolCalls(List.of(
                        new ToolCallRequest("latest-call", "lookup", 0, "{}"))).build(),
                ToolMessageEntity.builder().id("latest-call").name("lookup").toolOutput("complete latest result").build());

        List<Message> pruned = prune(original);

        assertEquals("earlier finding", pruned.get(2).text());
        assertTrue(((AiMessageEntity) pruned.get(2)).getToolCalls().isEmpty());

        try (Endpoint endpoint = new Endpoint()) {
            JsonNode anthropic = wire(endpoint, pruned, true);
            JsonNode openAi = wire(endpoint, pruned, false);

            assertFalse(anthropic.toString().contains("discarded-call"));
            assertFalse(openAi.toString().contains("discarded-call"));
            assertEquals("latest-call", anthropicBlock(anthropic, "tool_result").path("tool_use_id").asText());
            assertEquals("latest-call", openAiMessage(openAi, "tool").path("tool_call_id").asText());
            assertTrue(anthropic.toString().contains("complete latest result"));
            assertTrue(openAi.toString().contains("complete latest result"));
        }
    }

    // ------------------------------------------------------------------ fixtures

    /** Runs the real pruner over the messages and returns its product. */
    private static List<Message> prune(List<Message> messages) {
        AgentConfig config = AgentConfig.builder().maxTokens(MAX_TOKENS)
                .build();
        DefaultContextPruner pruner = new DefaultContextPruner(config,
                new ContextUsageReporter(new DefaultTokenizer(), MAX_TOKENS,
                        new RuntimeEventPublisher(List.of()), 1));
        List<Message> copy = new ArrayList<>(messages);
        Execution execution = Execution.builder().id("execution-1")
                .agentRequest(AgentRequest.builder().messages(copy).build())
                .messages(copy).build();
        pruner.compact(new ContextCompactRequest(execution, null));
        return execution.getMessages();
    }

    /** First Anthropic content block of the given type, over every message of the request. */
    private static JsonNode anthropicBlock(JsonNode body, String type) {
        for (JsonNode message : body.path("messages")) {
            for (JsonNode block : message.path("content")) {
                if (type.equals(block.path("type").asText())) {
                    return block;
                }
            }
        }
        return null;
    }

    /** First OpenAI message of the given role. */
    private static JsonNode openAiMessage(JsonNode body, String role) {
        for (JsonNode message : body.path("messages")) {
            if (role.equals(message.path("role").asText())) {
                return message;
            }
        }
        return null;
    }

    /** The OpenAI assistant message that carries the answer (the last assistant turn). */
    private static JsonNode openAiAssistant(JsonNode body) {
        JsonNode found = null;
        for (JsonNode message : body.path("messages")) {
            if ("assistant".equals(message.path("role").asText())) {
                found = message;
            }
        }
        assertNotNull(found, "the request must carry an assistant turn: " + body);
        return found;
    }

    /** The OpenAI assistant message that carries tool calls. */
    private static JsonNode openAiToolCallAssistant(JsonNode body) {
        for (JsonNode message : body.path("messages")) {
            if ("assistant".equals(message.path("role").asText()) && message.has("tool_calls")) {
                return message;
            }
        }
        return null;
    }

    /** Sends one request through the chosen provider and returns the JSON that reached the wire. */
    private static JsonNode wire(Endpoint endpoint, List<Message> messages, boolean anthropic)
            throws Exception {
        ModelConfig config = ModelConfig.builder()
                .baseUrl(endpoint.baseUrl()).apiKey("test-key")
                .modelName(anthropic ? "claude-sonnet-4-6" : "gpt-test")
                .timeout(Duration.ofSeconds(10)).maxTokens(4096)
                .sendThinking(true).returnThinking(true).build();
        ChatRequestEntity request = ChatRequestEntity.builder().messages(messages).build();
        if (anthropic) {
            new AnthropicChatModelProvider().create(config).chat(request);
        } else {
            new OpenAiChatModelProvider().create(config).chat(request);
        }
        JsonNode body = endpoint.requests.poll(10, TimeUnit.SECONDS);
        assertNotNull(body, "the provider must have sent a request");
        return body;
    }

    /** Local endpoint that accepts any request and answers with a minimal valid response. */
    private static final class Endpoint implements AutoCloseable {
        private final HttpServer server;
        private final LinkedBlockingQueue<JsonNode> requests = new LinkedBlockingQueue<>();

        private Endpoint() throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/v1/", exchange -> {
                try (exchange) {
                    JsonNode body = MAPPER.readTree(exchange.getRequestBody());
                    requests.add(body);
                    boolean anthropic = exchange.getRequestURI().getPath().endsWith("messages");
                    String response = anthropic
                            ? """
                            {"id":"msg-1","type":"message","role":"assistant","model":"claude-sonnet-4-6",
                            "content":[{"type":"text","text":"ok"}],"stop_reason":"end_turn",
                            "usage":{"input_tokens":10,"output_tokens":2}}
                            """
                            : """
                            {"id":"msg-1","model":"gpt-test","choices":[{"index":0,
                            "message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}]}
                            """;
                    byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, bytes.length);
                    exchange.getResponseBody().write(bytes);
                }
            });
            server.start();
        }

        private String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/";
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
