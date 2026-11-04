package openAi;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.summit.core.conf.ModelConfig;
import com.summit.core.exception.NonRetriableModelException;
import com.summit.core.exception.RetriableModelException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import openAi.image.DefaultImageModel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The provider's own error text has to reach the caller of the image model.
 *
 * <p>Reporting only the status code forces every caller — and every test — to assert on a number
 * that says nothing about why the request was rejected. The text is echoed, but bounded: a
 * misconfigured base URL can answer with an HTML page that has no place in a log line.</p>
 */
class ImageErrorMessageTest {

    private static final String GENERATION_FAILED = "Image generations failed with HTTP ";

    private final ObjectMapper json = new ObjectMapper();
    private final AtomicReference<String> body = new AtomicReference<>("");
    private final AtomicInteger status = new AtomicInteger(400);

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
    void errorMessageIsCarriedIntoTheException() {
        status.set(400);
        body.set("{\"error\":{\"message\":\"Your request was rejected by the safety system\",\"type\":\"invalid_request_error\"}}");

        NonRetriableModelException failure = assertThrows(NonRetriableModelException.class, this::generate);

        assertEquals(GENERATION_FAILED + "400: Your request was rejected by the safety system", failure.getMessage());
    }

    @Test
    void aBodyWithoutAnErrorMessageFallsBackToTheStatusOnlyMessage() {
        status.set(400);
        body.set("{\"error\":{\"type\":\"invalid_request_error\",\"code\":\"invalid_size\"}}");

        NonRetriableModelException failure = assertThrows(NonRetriableModelException.class, this::generate);

        assertEquals(GENERATION_FAILED + "400", failure.getMessage());
    }

    @Test
    void anUnparseableBodyFallsBackToTheStatusOnlyMessage() {
        status.set(502);
        body.set("<html><body>Bad gateway</body></html>");

        RetriableModelException failure = assertThrows(RetriableModelException.class, this::generate);

        assertEquals(GENERATION_FAILED + "502", failure.getMessage());
    }

    @Test
    void aLongErrorMessageIsTruncated() {
        status.set(400);
        String longMessage = "x".repeat(400);
        body.set("{\"error\":{\"message\":\"" + longMessage + "\"}}");

        NonRetriableModelException failure = assertThrows(NonRetriableModelException.class, this::generate);

        String message = failure.getMessage();
        assertTrue(message.startsWith(GENERATION_FAILED + "400: xxx"), message);
        assertTrue(message.endsWith("..."), message);
        assertTrue(message.length() < longMessage.length(), message);
    }

    /** A retriable status keeps its classification and still reports what the provider said. */
    @Test
    void aRetriableStatusKeepsItsClassificationAndTheDetail() {
        status.set(429);
        body.set("{\"error\":{\"message\":\"Rate limit reached for images\"}}");

        RetriableModelException failure = assertThrows(RetriableModelException.class, this::generate);

        assertEquals(GENERATION_FAILED + "429: Rate limit reached for images", failure.getMessage());
        assertInstanceOf(RuntimeException.class, failure);
    }

    private void generate() {
        DefaultImageModel model = new DefaultImageModel(client, json, ModelConfig.builder()
                .provider("openai").baseUrl(baseUrl).apiKey("test-key").modelName("gpt-image-1")
                .timeout(Duration.ofSeconds(10)).build());
        model.generate("a red bicycle");
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            exchange.getRequestBody().readAllBytes();
            byte[] bytes = body.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status.get(), bytes.length);
            exchange.getResponseBody().write(bytes);
        }
    }
}
