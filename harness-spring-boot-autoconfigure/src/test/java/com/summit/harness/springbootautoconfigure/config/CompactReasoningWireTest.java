package com.summit.harness.springbootautoconfigure.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.summit.adapter.langchain4j.model.provider.AnthropicCompactContextModelProvider;
import com.summit.adapter.langchain4j.model.provider.OpenAiChatModelProvider;
import com.summit.adapter.langchain4j.model.provider.OpenAiCompactContextModelProvider;
import com.summit.core.agent.AgentRequest;
import com.summit.core.conf.ModelBehavior;
import com.summit.core.conf.ModelConfig;
import com.summit.core.conversation.api.ChatRequestEntity;
import com.summit.core.conversation.message.UserMessageEntity;
import com.summit.core.model.ModelProviderRegistry;
import com.summit.core.model.chat.ChatModel;
import com.summit.harness.springbootautoconfigure.properties.CompactContextModelProperties;
import com.summit.harness.springbootautoconfigure.properties.agent.AgentChatProperties;
import com.summit.runtime.compact.RequestCompactModelResolver;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Covers the default properties through resolver selection and actual SDK request serialization. */
class CompactReasoningWireTest {
    private final ModelConfiguration configuration = new ModelConfiguration();
    private final ChatRequestEntity request = ChatRequestEntity.builder()
            .messages(List.of(UserMessageEntity.from("summarize"))).build();

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void defaultEffortIsOmittedForReusedDedicatedAndApplicationModels(boolean reuse) throws Exception {
        try (Endpoint endpoint = new Endpoint()) {
            ModelConfig compact = configuration.compactContextModelConfig(
                    new CompactContextModelProperties(), chat(endpoint, "gpt-test"));
            ModelConfig main = compact.toBuilder().provider("default-streaming")
                    .reasoningEffort("high").sendThinking(true).returnThinking(true)
                    .reuseModelForCompaction(reuse)
                    .compactModel(reuse ? null : new ModelConfig.CompactModelConfig(
                            endpoint.baseUrl(), "test-key", "gpt-test"))
                    .build();
            ModelProviderRegistry<ChatModel> providers = new ModelProviderRegistry<>();
            providers.register(new OpenAiChatModelProvider());
            providers.register(new OpenAiCompactContextModelProvider());
            ChatModel application = new OpenAiCompactContextModelProvider().create(compact);
            RequestCompactModelResolver resolver = new RequestCompactModelResolver(main,
                    ModelBehavior.of(compact), null, providers, application);

            assertNotNull(resolver.resolve(AgentRequest.builder().build()).chat(request).getAiMessageEntity());
            assertNotNull(application.chat(request).getAiMessageEntity());
            for (int index = 0; index < 2; index++) {
                JsonNode body = endpoint.next();
                assertFalse(body.has("reasoning_effort"));
                assertEquals(32768, body.path("max_tokens").asInt());
            }
            assertEquals("high", main.getReasoningEffort());
            assertTrue(main.isSendThinking());
            assertTrue(main.isReturnThinking());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"low", "off"})
    void explicitEndpointEffortIsPreserved(String effort) throws Exception {
        try (Endpoint endpoint = new Endpoint()) {
            CompactContextModelProperties properties = new CompactContextModelProperties();
            properties.setReasoningEffort(effort);
            ModelConfig compact = configuration.compactContextModelConfig(properties, chat(endpoint, "gpt-test"));
            new OpenAiCompactContextModelProvider().create(compact).chat(request);
            assertEquals(effort, endpoint.next().path("reasoning_effort").asText());
        }
    }

    @Test
    void nativeAnthropicStillDisablesThinkingByDefault() throws Exception {
        try (Endpoint endpoint = new Endpoint()) {
            ModelConfig compact = configuration.compactContextModelConfig(
                    new CompactContextModelProperties(), chat(endpoint, "claude-sonnet-4-6"));
            new AnthropicCompactContextModelProvider().create(compact).chat(request);
            JsonNode body = endpoint.next();
            assertEquals("disabled", body.path("thinking").path("type").asText());
            assertFalse(body.has("output_config"));
            assertFalse(body.has("reasoning_effort"));
        }
    }

    private AgentChatProperties chat(Endpoint endpoint, String model) {
        AgentChatProperties chat = new AgentChatProperties();
        chat.setBaseUrl(endpoint.baseUrl());
        chat.setApiKey("test-key");
        chat.setModelName(model);
        chat.setTimeout(Duration.ofSeconds(5));
        chat.setReasoningEffort("high");
        return chat;
    }

    private static final class Endpoint implements AutoCloseable {
        private final HttpServer server;
        private final LinkedBlockingQueue<JsonNode> requests = new LinkedBlockingQueue<>();

        private Endpoint() throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                JsonNode body = new ObjectMapper().readTree(exchange.getRequestBody());
                requests.add(body);
                String effort = body.path("reasoning_effort").asText("");
                boolean rejected = !effort.isEmpty() && !List.of("off", "low", "medium", "high", "xhigh", "max").contains(effort);
                String response = rejected ? """
                        {"error":{"message":"Invalid option: expected off|low|medium|high|xhigh|max",
                        "type":"invalid_request_error","param":"reasoning_effort"}}
                        """ : exchange.getRequestURI().getPath().endsWith("/messages") ? """
                        {"id":"summary","type":"message","role":"assistant","model":"claude-sonnet-4-6",
                        "content":[{"type":"text","text":"summary"}],"stop_reason":"end_turn",
                        "usage":{"input_tokens":10,"output_tokens":2}}
                        """ : """
                        {"id":"summary","model":"gpt-test","choices":[{"index":0,
                        "message":{"role":"assistant","content":"summary"},"finish_reason":"stop"}]}
                        """;
                byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(rejected ? 400 : 200, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            });
            server.start();
        }

        private String baseUrl() { return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1"; }

        private JsonNode next() throws InterruptedException {
            JsonNode body = requests.poll(5, TimeUnit.SECONDS);
            assertNotNull(body);
            return body;
        }

        public void close() { server.stop(0); }
    }
}
