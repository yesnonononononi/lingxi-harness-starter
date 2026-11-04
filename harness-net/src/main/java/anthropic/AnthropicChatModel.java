package anthropic;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.summit.core.conf.ModelConfig;
import com.summit.core.conversation.api.ChatRequestEntity;
import com.summit.core.conversation.api.ChatResponseEntity;
import com.summit.core.exception.NonRetriableModelException;
import com.summit.core.exception.RetriableModelException;
import com.summit.core.model.chat.ChatModel;
import com.summit.core.model.streaming.StreamingChatModel;
import com.summit.core.model.streaming.StreamingChatResponseHandler;
import lombok.RequiredArgsConstructor;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

@RequiredArgsConstructor
public class AnthropicChatModel implements ChatModel, StreamingChatModel {

    /**
     * Anthropic error codes that mean "the account cannot spend anymore".
     *
     * <p>They can arrive on a 429 next to a genuine rate limit, so the status alone cannot tell the
     * two apart.</p>
     */
    private static final Set<String> QUOTA_ERROR_CODES = Set.of("insufficient_quota",
            "billing_error", "billing_hard_limit_reached");

    private final ModelConfig modelConfig;
    private final HttpClient httpClient;
    private final ObjectMapper JSON;

    @Override
    public ChatResponseEntity chat(ChatRequestEntity request) {
        try {
            HttpResponse<String> response = httpClient.send(buildRequest(request, false), HttpResponse.BodyHandlers.ofString());
            checkStatus(response.statusCode(), response.body(), JSON);
            return ResponseSerializer.serialize(JSON.readTree(response.body()), JSON, modelConfig.isReturnThinking());
        } catch (JsonProcessingException error) {
            throw new NonRetriableModelException("Invalid Anthropic request or response JSON", error);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new NonRetriableModelException("Anthropic request interrupted", error);
        } catch (IOException error) {
            throw new RetriableModelException("Anthropic transport failed", error);
        }
    }

    @Override
    public void chat(ChatRequestEntity request, StreamingChatResponseHandler handler) {
        HttpRequest httpRequest;
        try {
            httpRequest = buildRequest(request, true);
        } catch (JsonProcessingException error) {
            handler.onError(new NonRetriableModelException("Invalid Anthropic request JSON", error));
            return;
        } catch (RuntimeException error) {
            handler.onError(error);
            return;
        }
        new AnthropicStreaming(httpClient, JSON, handler, modelConfig.isReturnThinking()).start(httpRequest);
    }

    private HttpRequest buildRequest(ChatRequestEntity request, boolean stream) throws JsonProcessingException {
        AnthropicModelRequest body = AnthropicModelRequest.from(request, modelConfig, JSON);
        body.setStream(stream);
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(resolveEndpoint(modelConfig.getBaseUrl()))
                .header("Authorization", "Bearer " + modelConfig.getApiKey())
                .header("anthropic-version", "2023-06-01")
                .header("Content-Type", "application/json");
        if (stream) builder.header("Accept", "text/event-stream");
        if (modelConfig.getTimeout() != null) builder.timeout(modelConfig.getTimeout());
        return builder.POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build();
    }

    private static URI resolveEndpoint(String baseUrl) {
        URI base = URI.create(Objects.requireNonNullElse(baseUrl, "https://api.anthropic.com/v1"));
        String path = base.getRawPath().replaceFirst("/+$", "");
        if (!path.endsWith("/messages")) {
            path += path.endsWith("/v1") ? "/messages" : "/v1/messages";
        }
        String query = base.getRawQuery();
        return base.resolve(query == null ? path : path + "?" + query);
    }

    /**
     * Classifies a failed Messages API response.
     *
     * <p>A 429 is normally a rate limit and a retry can ride it out, but Anthropic also answers 429
     * when the account has run out of budget ({@code billing_error}, {@code *_billing_*}). That is a
     * terminal state: retrying spends another request on an account that cannot pay for it. The
     * provider's own error code therefore wins over the status class, exactly as it did in the
     * retired {@code ProviderErrorMapper}.</p>
     *
     * @param status HTTP status of the response
     * @param body   response body, used only to read the provider's error code; may be null
     * @param json   mapper that reads the error document
     */
    static void checkStatus(int status, String body, ObjectMapper json) {
        if (status == 429 && quotaExhausted(body, json)) {
            throw new NonRetriableModelException("Anthropic request failed with HTTP " + status, null);
        }
        if (status == 408 || status == 429 || status >= 500) {
            throw new RetriableModelException("Anthropic request failed with HTTP " + status, null);
        }
        if (status != 200) {
            throw new NonRetriableModelException("Anthropic request failed with HTTP " + status, null);
        }
    }

    /** @return whether the error body names an exhausted quota or a billing limit */
    private static boolean quotaExhausted(String body, ObjectMapper json) {
        if (body == null || body.isBlank() || json == null) return false;
        JsonNode root;
        try {
            root = json.readTree(body);
        } catch (JsonProcessingException malformed) {
            // Not a provider error document: classify by status alone, as before.
            return false;
        }
        if (root == null) return false;
        JsonNode error = root.path("error");
        return isQuotaCode(error.path("type").asText(null)) || isQuotaCode(error.path("code").asText(null));
    }

    private static boolean isQuotaCode(String candidate) {
        if (candidate == null || candidate.isBlank()) return false;
        String code = candidate.strip().toLowerCase(Locale.ROOT);
        return QUOTA_ERROR_CODES.contains(code) || code.startsWith("billing_");
    }
}
