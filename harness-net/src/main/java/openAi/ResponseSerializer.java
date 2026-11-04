package openAi;

import com.summit.core.conversation.api.ChatResponseEntity;
import com.summit.core.conversation.api.ChatResponseEntity.FinishReason;
import com.summit.core.conversation.api.ToolCallRequest;
import com.summit.core.conversation.message.AiMessageEntity;
import com.summit.core.conversation.message.TokenUsageEntity;

import java.util.ArrayList;
import java.util.List;

/** Converts the first chat completion choice into the framework response. */
public class ResponseSerializer {
    public ChatResponseEntity serialize(ChatCompletionResponse response) {
        var choices = response.getChoices();
        var choice = choices == null || choices.isEmpty() ? null : choices.getFirst();
        if (choice == null || choice.getMessage() == null) {
            throw new IllegalArgumentException("Chat completion response is missing its first choice message");
        }

        ChatCompletionResponse.Choice.Message message = choice.getMessage();
        return ChatResponseEntity.builder()
                .aiMessageEntity(AiMessageEntity.builder()
                        .text(message.getContent())
                        .thinking(message.getReasoningContent())
                        .toolCalls(toolCallsOf(message.getToolCalls()))
                        .build())
                .meta(ChatResponseEntity.Meta.builder()
                        .id(response.getId())
                        .modelName(response.getModel())
                        .finishReason(finishReasonOf(choice.getFinishReason()))
                        .build())
                .tokenUsage(usageOf(response.getUsage()))
                .build();
    }

    private List<ToolCallRequest> toolCallsOf(List<ChatCompletionResponse.Choice.Message.ToolCall> calls) {
        if (calls == null || calls.isEmpty()) {
            return List.of();
        }

        List<ToolCallRequest> requests = new ArrayList<>(calls.size());
        for (int index = 0; index < calls.size(); index++) {
            var call = calls.get(index);
            if (call == null || call.getFunction() == null) {
                throw new IllegalArgumentException("Chat completion tool call at index " + index + " is missing its function");
            }
            var function = call.getFunction();
            requests.add(new ToolCallRequest(call.getId(), function.getName(), index, function.getArguments()));
        }
        return requests;
    }

    private FinishReason finishReasonOf(String reason) {
        return switch (reason) {
            case "stop" -> FinishReason.STOP;
            case "length" -> FinishReason.LENGTH;
            case "tool_calls" -> FinishReason.TOOL_EXECUTION;
            case "content_filter" -> FinishReason.CONTENT_FILTER;
            case null, default -> FinishReason.OTHER;
        };
    }

    private TokenUsageEntity usageOf(ChatCompletionResponse.Usage usage) {
        if (usage == null) {
            return TokenUsageEntity.empty();
        }
        Integer reportedCacheHit = usage.getCacheHitTokens();
        if (reportedCacheHit == null && usage.getPromptTokensDetails() != null) {
            reportedCacheHit = usage.getPromptTokensDetails().getCachedTokens();
        }
        int cacheHit = reportedCacheHit == null ? 0 : reportedCacheHit;
        Integer cacheMiss = usage.getCacheMissTokens();
        int input = usage.getPromptTokens() == null
                ? cacheHit + (cacheMiss == null ? 0 : cacheMiss) : usage.getPromptTokens();
        int output = usage.getCompletionTokens() == null ? 0 : usage.getCompletionTokens();
        int total = usage.getTotalTokens() == null ? input + output : usage.getTotalTokens();
        int reasoning = usage.getCompletionTokensDetails() == null ? 0 : usage.getCompletionTokensDetails().getReasoningTokens();
        return new TokenUsageEntity(total, new TokenUsageEntity.InputTokens(input, cacheHit, 0, null, cacheMiss),
                new TokenUsageEntity.OutputTokens(output, reasoning));
    }
}
