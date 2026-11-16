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
            Built built = buildRequest(request, false);
            HttpResponse<String> response = httpClient.send(built.http(), HttpResponse.BodyHandlers.ofString());
            checkStatus(response.statusCode(), response.body(), JSON, built.target());
            return ResponseSerializer.serialize(JSON.readTree(response.body()), JSON,
                    modelConfig.isReturnThinking(), built.thinkingBinding());
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
        Built built;
        try {
            built = buildRequest(request, true);
        } catch (JsonProcessingException error) {
            handler.onError(new NonRetriableModelException("Invalid Anthropic request JSON", error));
            return;
        } catch (RuntimeException error) {
            handler.onError(error);
            return;
        }
        new AnthropicStreaming(httpClient, JSON, handler, modelConfig.isReturnThinking(), built.thinkingBinding(),
                built.target())
                .start(built.http());
    }

    /** A ready-to-send request: its HTTP form, its thinking fingerprint and how to name it in a log. */
    private record Built(HttpRequest http, String thinkingBinding, String target) {
    }

    private Built buildRequest(ChatRequestEntity request, boolean stream) throws JsonProcessingException {
        AnthropicModelRequest body = AnthropicModelRequest.from(request, modelConfig, JSON);
        body.setStream(stream);
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(resolveEndpoint(modelConfig.getBaseUrl()))
                .header("Authorization", "Bearer " + modelConfig.getApiKey())
                .header("anthropic-version", "2023-06-01")
                .header("Content-Type", "application/json");
        if (stream) builder.header("Accept", "text/event-stream");
        if (modelConfig.getTimeout() != null) builder.timeout(modelConfig.getTimeout());
        HttpRequest http = builder.POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build();
        return new Built(http, AnthropicThinkingBinding.fingerprint(modelConfig.getModelName(), body.getTools()),
                target(modelConfig.getModelName(), http.uri()));
    }

    /**
     * How a failure names what it called: a provider message like {@code permission_error: the request
     * was refused} says nothing about which model on which endpoint answered it, and reaching several
     * relays from one installation makes that the only question worth asking.
     *
     * <p>The query string is dropped, not echoed: a relay may carry its credential there.</p>
     */
    static String target(String modelName, URI uri) {
        String host = uri.getHost();
        String endpoint = host == null ? uri.getRawPath()
                : uri.getScheme() + "://" + host + (uri.getPort() < 0 ? "" : ":" + uri.getPort()) + uri.getRawPath();
        return "model=" + modelName + ", endpoint=" + endpoint;
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
     * <p>The body is also the only place that says <em>why</em> a request was rejected. It is read
     * once, for both the classification and the message: a bare {@code HTTP 400} in a log cannot be
     * diagnosed after the fact, and the rejected request body is not repeatable evidence — the
     * conversation has already moved on.</p>
     *
     * @param status HTTP status of the response
     * @param body   response body, carrying the provider's error type and message; may be null
     * @param json   mapper that reads the error document
     * @param target model and endpoint the request went to, so a bare provider refusal stays locatable
     */
    static void checkStatus(int status, String body, ObjectMapper json, String target) {
        ProviderError error = ProviderError.read(body, json);
        if (status == 429 && error.isQuota()) {
            throw new NonRetriableModelException(error.describe(status, target), null);
        }
        if (status == 408 || status == 429 || status >= 500) {
            throw new RetriableModelException(error.describe(status, target), null);
        }
        if (status != 200) {
            throw new NonRetriableModelException(error.describe(status, target), null);
        }
    }

    /**
     * The provider's error document, kept as evidence instead of being dropped after classification.
     *
     * <p>A body that is not an error document at all — a gateway page, a truncated read — is kept
     * verbatim: that text is usually the whole explanation.</p>
     */
    private record ProviderError(String type, String code, String message, String raw) {

        static final ProviderError EMPTY = new ProviderError(null, null, null, null);

        static ProviderError read(String body, ObjectMapper json) {
            if (body == null || body.isBlank()) return EMPTY;
            String raw = body.strip();
            if (json == null) return new ProviderError(null, null, null, raw);
            JsonNode error;
            try {
                JsonNode root = json.readTree(raw);
                error = root == null ? null : root.path("error");
            } catch (JsonProcessingException malformed) {
                // Not a provider error document: classify by status, but still say what came back.
                return new ProviderError(null, null, null, raw);
            }
            if (error == null || !error.isObject()) return new ProviderError(null, null, null, raw);
            return new ProviderError(error.path("type").asText(null), error.path("code").asText(null),
                    error.path("message").asText(null), raw);
        }

        /** @return whether the document names an exhausted quota or a billing limit */
        boolean isQuota() {
            return isQuotaCode(code) || isQuotaCode(type);
        }

        /**
         * The exception message: the status, then the provider's own words whenever there are any.
         *
         * <p>Verbatim on purpose — a rejected request body is only ever explained once, and the log
         * appender already handles how much of a line it keeps.</p>
         */
        String describe(int status, String target) {
            String detail = detail();
            String prefix = "Anthropic request failed with HTTP " + status
                    + (target == null || target.isBlank() ? "" : " [" + target + "]");
            return detail.isEmpty() ? prefix : prefix + ": " + detail;
        }

        private String detail() {
            String label = code != null ? code : type;
            if (label != null) return message == null ? label : label + ": " + message;
            if (message != null) return message;
            return raw == null ? "" : raw;
        }
    }

    private static boolean isQuotaCode(String candidate) {
        if (candidate == null || candidate.isBlank()) return false;
        String code = candidate.strip().toLowerCase(Locale.ROOT);
        return QUOTA_ERROR_CODES.contains(code) || code.startsWith("billing_");
    }
}
