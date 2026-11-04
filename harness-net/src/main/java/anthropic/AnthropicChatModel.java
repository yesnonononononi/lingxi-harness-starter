package anthropic;

import com.fasterxml.jackson.core.JsonProcessingException;
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
import java.util.Objects;

@RequiredArgsConstructor
public class AnthropicChatModel implements ChatModel, StreamingChatModel {
    private final ModelConfig modelConfig;
    private final HttpClient httpClient;
    private final ObjectMapper JSON;

    @Override
    public ChatResponseEntity chat(ChatRequestEntity request) {
        try {
            HttpResponse<String> response = httpClient.send(buildRequest(request, false), HttpResponse.BodyHandlers.ofString());
            checkStatus(response.statusCode());
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

    static void checkStatus(int status) {
        if (status == 408 || status == 429 || status >= 500) {
            throw new RetriableModelException("Anthropic request failed with HTTP " + status, null);
        }
        if (status != 200) {
            throw new NonRetriableModelException("Anthropic request failed with HTTP " + status, null);
        }
    }
}
