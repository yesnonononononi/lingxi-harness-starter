package openAi;

import anthropic.AnthropicChatModel;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.summit.core.conf.ModelConfig;
import com.summit.core.conversation.api.ChatRequestEntity;
import com.summit.core.conversation.message.UserMessageEntity;
import com.summit.core.exception.NonRetriableModelException;
import com.summit.core.exception.RetriableModelException;
import com.summit.core.model.chat.ChatModel;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A 429 has two very different meanings and only the provider's error code separates them.
 *
 * <p>A rate limit is transient and worth a retry. An exhausted quota is terminal: the account cannot
 * pay for the request, so a retry costs money and cannot succeed. Both arrive as 429, which is why
 * the retired {@code ProviderErrorMapper} classified by code rather than status and why this
 * implementation has to do the same.</p>
 */
class QuotaErrorClassificationTest {

    private final ObjectMapper json = new ObjectMapper();
    private final AtomicReference<String> body = new AtomicReference<>("");
    private final AtomicInteger status = new AtomicInteger(429);
    private final AtomicInteger requests = new AtomicInteger();

    private HttpServer server;
    private HttpClient client;
    private String baseUrl;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        client = HttpClient.newHttpClient();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
        client.close();
    }

    @Test
    void openAiQuotaExhaustionIsTerminal() {
        body.set("{\"error\":{\"message\":\"You exceeded your current quota\","
                + "\"type\":\"insufficient_quota\",\"code\":\"insufficient_quota\"}}");

        NonRetriableModelException failure = assertThrows(NonRetriableModelException.class, this::openAiCall);

        assertTrue(failure.getMessage().contains("insufficient_quota"), failure.getMessage());
        assertTrue(failure.getMessage().contains("HTTP 429"), failure.getMessage());
        assertEquals(1, requests.get(), "a terminal quota error must not be retried");
    }

    @Test
    void openAiBillingCodeIsTerminalEvenWithoutTheQuotaType() {
        body.set("{\"error\":{\"message\":\"Billing hard limit has been reached\","
                + "\"type\":\"invalid_request_error\",\"code\":\"billing_hard_limit_reached\"}}");

        assertThrows(NonRetriableModelException.class, this::openAiCall);
    }

    @Test
    void openAiRateLimitStaysRetriable() {
        body.set("{\"error\":{\"message\":\"Rate limit reached for gpt-test\","
                + "\"type\":\"rate_limit_error\",\"code\":\"rate_limit_exceeded\"}}");

        assertThrows(RetriableModelException.class, this::openAiCall);
    }

    @Test
    void anUnparseable429BodyStaysRetriable() {
        body.set("upstream proxy says slow down");

        assertThrows(RetriableModelException.class, this::openAiCall);
    }

    @Test
    void anthropicBillingCodeIsTerminal() {
        body.set("{\"type\":\"error\",\"error\":{\"type\":\"billing_error\","
                + "\"message\":\"Your credit balance is too low\"}}");

        assertThrows(NonRetriableModelException.class, this::anthropicCall);
    }

    @Test
    void anthropicRateLimitStaysRetriable() {
        body.set("{\"type\":\"error\",\"error\":{\"type\":\"rate_limit_error\","
                + "\"message\":\"Number of requests exceeded your rate limit\"}}");

        assertThrows(RetriableModelException.class, this::anthropicCall);
    }

    private void openAiCall() {
        ModelChatResolver resolver = new ModelChatResolver(
                new RequestBodyFactory(new MessageSerializer(json), json), json, new ResponseSerializer());
        ChatModel model = new DefaultChatModel(config("gpt-test"), resolver, client);
        model.chat(request());
    }

    private void anthropicCall() {
        ChatModel model = new AnthropicChatModel(config("claude-sonnet-4-6"), client, json);
        model.chat(request());
    }

    private static ChatRequestEntity request() {
        return ChatRequestEntity.builder().messages(List.of(UserMessageEntity.from("question"))).build();
    }

    private ModelConfig config(String modelName) {
        return ModelConfig.builder().provider("test").baseUrl(baseUrl).apiKey("test-key")
                .modelName(modelName).timeout(Duration.ofSeconds(10)).maxTokens(4096).build();
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            requests.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            byte[] bytes = body.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status.get(), bytes.length);
            exchange.getResponseBody().write(bytes);
        }
    }
}
