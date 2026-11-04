package anthropic;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.summit.core.conversation.api.ChatResponseEntity;
import com.summit.core.conversation.api.ChatResponseEntity.FinishReason;
import com.summit.core.conversation.api.ToolCallRequest;
import com.summit.core.conversation.message.AiMessageEntity;
import com.summit.core.conversation.message.TokenUsageEntity;
import com.summit.core.exception.NonRetriableModelException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class ResponseSerializer {
    public static final String NATIVE_CONTENT_ATTRIBUTE = "anthropic_content_blocks";

    static ChatResponseEntity serialize(JsonNode wire, ObjectMapper mapper, boolean returnThinking)
            throws JsonProcessingException {
        AnthropicResponse response = mapper.treeToValue(wire, AnthropicResponse.class);
        ChatResponseEntity result = serialize(response);
        if (returnThinking) {
            StringBuilder thinking = new StringBuilder();
            for (AnthropicResponse.Content block : response.getContent()) {
                if (block instanceof AnthropicResponse.Thinking thought && thought.getThinking() != null) {
                    thinking.append(thought.getThinking());
                }
            }
            if (!thinking.isEmpty()) result.getAiMessageEntity().setThinking(thinking.toString());
        }
        if (response.getContent().stream().anyMatch(block -> block instanceof AnthropicResponse.Thinking
                || block instanceof AnthropicResponse.RedactedThinking)) {
            result.getAiMessageEntity().setAttributes(Map.of(NATIVE_CONTENT_ATTRIBUTE, wire.path("content").deepCopy()));
        }
        return result;
    }

    public static ChatResponseEntity serialize(AnthropicResponse anthropicResponse) {
        Objects.requireNonNull(anthropicResponse);
        List<AnthropicResponse.Content> content = anthropicResponse.getContent();
        if (content == null || content.isEmpty()) {
            throw new NonRetriableModelException("Anthropic response is missing content", null);
        }
        StringBuilder text = new StringBuilder();
        for (AnthropicResponse.Content block : content) {
            if (block instanceof AnthropicResponse.AssistantText assistant && assistant.getText() != null) {
                text.append(assistant.getText());
            }
        }

        return ChatResponseEntity.builder()
                .aiMessageEntity(AiMessageEntity.builder()
                        .text(text.isEmpty() ? null : text.toString())
                        .toolCalls(toolCallsOf(content))
                        .build())
                .meta(ChatResponseEntity.Meta.builder()
                        .id(anthropicResponse.getId())
                        .modelName(anthropicResponse.getModel())
                        .finishReason(switch (anthropicResponse.getStopReason()) {
                            case "end_turn", "stop_sequence" -> FinishReason.STOP;
                            case "max_tokens" -> FinishReason.LENGTH;
                            case "tool_use" -> FinishReason.TOOL_EXECUTION;
                            case null, default -> FinishReason.OTHER;
                        })
                        .build())
                .tokenUsage(usageOf(anthropicResponse.getUsage()))
                .build();
    }

    private static List<ToolCallRequest> toolCallsOf(List<AnthropicResponse.Content> contents) {
        List<ToolCallRequest> result = new ArrayList<>();
        for (AnthropicResponse.Content content : contents) {
            if (content instanceof AnthropicResponse.ToolUse toolUse) {
                if (toolUse.getInput() == null) {
                    throw new NonRetriableModelException("Anthropic tool_use is missing input", null);
                }
                result.add(new ToolCallRequest(toolUse.getId(), toolUse.getName(), result.size(), toolUse.getInput().toString()));
            }
        }
        return result;
    }

    private static TokenUsageEntity usageOf(AnthropicResponse.Usage usage) {
        if (usage == null) return TokenUsageEntity.empty();
        int input = usage.getInputTokens() + usage.getCacheCreationInputTokens() + usage.getCacheReadInputTokens();
        return new TokenUsageEntity(input + usage.getOutputTokens(),
                new TokenUsageEntity.InputTokens(input, usage.getCacheReadInputTokens()),
                TokenUsageEntity.OutputTokens.of(usage.getOutputTokens()));
    }
}
