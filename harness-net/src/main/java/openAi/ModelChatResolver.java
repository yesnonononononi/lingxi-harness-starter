package openAi;



import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.summit.core.conf.ModelConfig;
import com.summit.core.conversation.api.ChatRequestEntity;
import com.summit.core.conversation.api.ChatResponseEntity;
import com.summit.core.exception.NonRetriableModelException;
import com.summit.core.exception.RetriableModelException;
import com.summit.core.model.streaming.StreamingChatResponseHandler;
import com.summit.core.model.streaming.StreamingHandler;
import com.summit.core.runtime.loop.ExecutionControlSignal;
import com.summit.core.runtime.loop.suspension.ExecutionInterruptedException;
import lombok.AccessLevel;
import lombok.Data;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import java.io.BufferedReader;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;


@RequiredArgsConstructor
public class ModelChatResolver {

    /**
     * Provider error codes that mean "the account cannot spend anymore".
     *
     * <p>They arrive on a 429 next to genuine rate limits, so the status alone cannot separate them.
     * The retired {@code ProviderErrorMapper} carried the same list; losing it made a quota
     * exhaustion look retriable and charged the account for a second attempt.</p>
     */
    private static final Set<String> QUOTA_ERROR_CODES = Set.of("insufficient_quota",
            "billing_hard_limit_reached", "billing_error", "organization_usage_limit_exceeded",
            "usage_limit_exceeded");

    private final RequestBodyFactory requestBodyFactory;
    private final ObjectMapper JSON;
    private final ResponseSerializer responseSerializer;


    @RequiredArgsConstructor
    @Data
    public static class StreamingBuffer {
        private String id;
        private String name;

        @Getter(AccessLevel.PRIVATE)
        private final StringBuilder arguments = new StringBuilder();

        void appendArguments(String fragment) {
            if (fragment != null) arguments.append(fragment);
        }

        ChatCompletionResponse.Choice.Message.ToolCall finish() {
            if (id == null || id.isBlank() || name == null || name.isBlank()) {
                throw new IllegalStateException("Incomplete streamed tool call");
            }

            var function = new ChatCompletionResponse.Choice.Message.ToolCall.Function();
            function.setName(name);
            function.setArguments(arguments.toString());

            var call = new ChatCompletionResponse.Choice.Message.ToolCall();
            call.setId(id);
            call.setFunction(function);
            return call;
        }
    }

    public ChatResponseEntity resolve(HttpClient httpClient,
                                      ModelConfig modelConfig,
                                      ChatRequestEntity chatRequestEntity,
                                      ExecutionControlSignal signal,
                                      StreamingChatResponseHandler streamingModelResponseHandler

    ) {

        boolean stream = modelConfig.isStream();

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(resolveEndpoint(modelConfig.getBaseUrl()))
                .header("Content-Type", "application/json");
        if (modelConfig.getApiKey() != null) builder.header("Authorization", "Bearer " + modelConfig.getApiKey());

        if (modelConfig.getTimeout() != null) builder.timeout(modelConfig.getTimeout());

        HttpRequest httpRequest = builder
                .POST(HttpRequest.BodyPublishers.ofString(this.requestBodyFactory.build(chatRequestEntity, modelConfig), StandardCharsets.UTF_8))
                .build();
        try {
            if (stream) {
                this.streaming(httpClient, httpRequest, signal, streamingModelResponseHandler,modelConfig);
                return null;
            }
            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            String body = response.body();
            int statusCode = response.statusCode();
            switch (statusCode) {
                case 200 -> {
                    var responseEntity = responseSerializer.serialize(JSON.readValue(body, ChatCompletionResponse.class));
                    if (!modelConfig.isReturnThinking()) responseEntity.getAiMessageEntity().setThinking(null);
                    return responseEntity;
                }
                default -> throw statusError(statusCode, body);
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new NonRetriableModelException("Model request interrupted", error);
        } catch (IOException je) {
            throw new RetriableModelException(je.getMessage(), je);
        }
    }

    public void streaming(HttpClient httpClient,
                          HttpRequest httpRequest,
                          ExecutionControlSignal signal,
                          StreamingChatResponseHandler streamingModelResponseHandler,
                          ModelConfig modelConfig
    ) throws IOException, InterruptedException {

        TreeMap<Integer, StreamingBuffer> toolCallBuffer = new TreeMap<>();
        ChatCompletionResponse completionResponse = new ChatCompletionResponse();
        StringBuilder text = new StringBuilder();
        StringBuilder thinking = new StringBuilder();
        checkInterruption(signal, null);
        HttpResponse<InputStream> streamHttpResponse;
        try {
            streamHttpResponse = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new ExecutionInterruptedException(ExecutionInterruptedException.Kind.CANCEL, "Model stream interrupted");
        }
        StreamingHandler streamingHandler = buildStreamingHandler(streamHttpResponse);
        String finishReason = null;
        if (streamHttpResponse.statusCode() != 200) {
            try (InputStream body = streamHttpResponse.body()) {
                String errorBody = new String(body.readAllBytes(), StandardCharsets.UTF_8);
                throw statusError(streamHttpResponse.statusCode(), errorBody);
            }
        }

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(streamHttpResponse.body(), StandardCharsets.UTF_8))) {
            String line;

            boolean done = false, receivedChoice = false;

            checkInterruption(signal, streamingHandler);
            while ((line = reader.readLine()) != null) {
                ChatCompletionResponse.Choice choice;

                checkInterruption(signal, streamingHandler);

                if (!line.startsWith("data:")) continue;

                String payload = line.substring(5).strip();
                if (payload.isEmpty()) continue;
                if ("[DONE]".equals(payload)) {
                    done = true;
                    break;
                }

                ChatCompletionResponse response = JSON.readValue(payload, ChatCompletionResponse.class);
                if (response.getError() != null && !response.getError().isNull()) {
                    throw new RetriableModelException("OpenAI-compatible stream returned an error event", null);
                }


                ChatCompletionResponse.Usage usage = response.getUsage();
                if (usage != null) {
                    completionResponse.setUsage(usage);
                }
                if (completionResponse.getId() == null) completionResponse.setId(response.getId());
                if (completionResponse.getModel() == null) completionResponse.setModel(response.getModel());
                List<ChatCompletionResponse.Choice> choices = response.getChoices();

                if (choices == null || choices.isEmpty()) continue;
                choice = choices.getFirst();
                receivedChoice = true;


                if (choice.getFinishReason() != null) {
                    finishReason = choice.getFinishReason();
                }


                ChatCompletionResponse.Choice.Delta delta = choice.getDelta();
                if (delta == null) continue;
                String content = delta.getContent();
                String reasoningContent = delta.getReasoningContent();
                List<ChatCompletionResponse.Choice.Delta.ToolCallDelta> toolCalls = delta.getToolCalls();


                if (content != null && !content.isEmpty()) {
                    text.append(content);
                    streamingModelResponseHandler.onPartialResponse(content, streamingHandler);
                    checkInterruption(signal, streamingHandler);
                }
                if (reasoningContent != null && !reasoningContent.isEmpty() && modelConfig.isReturnThinking()) {
                    thinking.append(reasoningContent);
                    streamingModelResponseHandler.onPartialThinking(reasoningContent, streamingHandler);
                    checkInterruption(signal, streamingHandler);
                }

                if (toolCalls != null && !toolCalls.isEmpty()) {
                    for (ChatCompletionResponse.Choice.Delta.ToolCallDelta toolCall : toolCalls) {

                        Integer index = Objects.requireNonNullElse(toolCall.getIndex(), 0);

                        String toolCallId = toolCall.getId();
                        StreamingBuffer buffer = toolCallBuffer.computeIfAbsent(index, i -> new StreamingBuffer());
                        if (toolCallId != null) buffer.setId(toolCallId);

                        ChatCompletionResponse.Choice.Delta.ToolCallDelta.Function function = toolCall.getFunction();
                        String arguments = function == null ? null : function.getArguments();
                        String name = function == null ? null : function.getName();
                        if (name != null) buffer.setName(name);
                        buffer.appendArguments(arguments);

                        streamingModelResponseHandler.onPartialToolCall(index, toolCallId, name, arguments, streamingHandler);
                        checkInterruption(signal, streamingHandler);
                    }
                }


            }

            checkInterruption(signal, streamingHandler);
            if (!receivedChoice || (!done && (finishReason == null || finishReason.isBlank()))) {
                throw new EOFException("Model stream ended before completion");
            }

        } catch (IOException e) {
            checkInterruption(signal, streamingHandler);
            throw new RetriableModelException(e.getMessage(), e);
        }

        checkInterruption(signal, streamingHandler);
        end(completionResponse, text, thinking, toolCallBuffer, finishReason, streamingModelResponseHandler);
    }

    /**
     * Classifies a failed HTTP response.
     *
     * <p>A 429 is normally a rate limit, which a retry can ride out. It is also how OpenAI reports a
     * spent quota ({@code insufficient_quota}, {@code billing_*}), and that is a terminal state: the
     * account is out of budget, so retrying spends money on a request that cannot succeed. The
     * provider's own error code therefore wins over the status class.</p>
     */
    private RuntimeException statusError(int status, String body) {
        String message = "Model request failed with HTTP " + status + ": " + body;
        if (status == 429 && quotaExhausted(body)) {
            return new NonRetriableModelException(message, null);
        }
        if (status == 408 || status == 429 || status >= 500) {
            return new RetriableModelException(message, null);
        }
        return new NonRetriableModelException(message, null);
    }

    /** @return whether the error body names an exhausted quota or a billing limit */
    private boolean quotaExhausted(String body) {
        if (body == null || body.isBlank()) return false;
        JsonNode root;
        try {
            root = JSON.readTree(body);
        } catch (JsonProcessingException malformed) {
            // Not a provider error document: fall back to classifying by status alone.
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

    private static URI resolveEndpoint(String baseUrl) {
        URI base = URI.create(Objects.requireNonNullElse(baseUrl, "https://api.openai.com/v1"));
        String path = base.getRawPath().replaceFirst("/+$", "");
        if (!path.endsWith("/chat/completions")) path += "/chat/completions";
        String query = base.getRawQuery();
        return base.resolve(query == null ? path : path + "?" + query);
    }

    private static void checkInterruption(ExecutionControlSignal signal, StreamingHandler streamingHandler) {
        if (signal != null) signal.checkInterruption();
        if (Thread.currentThread().isInterrupted() || (streamingHandler != null && streamingHandler.isCancelled())) {
            throw new ExecutionInterruptedException(ExecutionInterruptedException.Kind.CANCEL, "Model stream cancelled");
        }
    }

    private void end(ChatCompletionResponse completionResponse,
                     StringBuilder text,
                     StringBuilder thinking,
                     TreeMap<Integer, StreamingBuffer> toolCallBuffer,
                     String finishReason,
                     StreamingChatResponseHandler streamingModelResponseHandler
    ){
        this.aggregateResult(completionResponse,text,thinking,toolCallBuffer,finishReason);
        streamingModelResponseHandler.onFinalResponse(responseSerializer.serialize(completionResponse));
    }


    private void aggregateResult(ChatCompletionResponse completionResponse,
                                 StringBuilder text,
                                 StringBuilder thinking,
                                 TreeMap<Integer, StreamingBuffer> toolCallBuffer,
                                 String finishReason) {

        List<ChatCompletionResponse.Choice> choices = completionResponse.getChoices();

        ChatCompletionResponse.Choice first;
        if (choices.isEmpty()) {
            first = new ChatCompletionResponse.Choice();
            choices.add(first);
        } else {
            first = choices.getFirst();
        }

        ChatCompletionResponse.Choice.Message message = first.getMessage();
        if (message == null) {
            message = new ChatCompletionResponse.Choice.Message();
            first.setMessage(message);
        }

        message.setContent(text.isEmpty() ? null : text.toString());
        message.setReasoningContent(thinking.isEmpty() ? null : thinking.toString());
        if (!toolCallBuffer.isEmpty()) {
            message.setToolCalls(toolCallBuffer.values().stream()
                    .map(StreamingBuffer::finish)
                    .toList());
        }

        first.setFinishReason(finishReason);
    }


    private static StreamingHandler buildStreamingHandler(HttpResponse<InputStream> streamHttpResponse) {
        AtomicBoolean canceled = new AtomicBoolean(false);

        return new StreamingHandler() {

            @Override
            public void cancel() {
                if (!canceled.compareAndSet(false, true)) return;
                try {
                    streamHttpResponse.body().close();
                } catch (IOException ignore) {
                }
            }

            @Override
            public boolean isCancelled() {
                return canceled.get();
            }
        };
    }


}
