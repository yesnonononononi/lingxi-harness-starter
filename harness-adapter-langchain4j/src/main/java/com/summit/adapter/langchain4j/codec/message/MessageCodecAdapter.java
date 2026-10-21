package com.summit.adapter.langchain4j.codec.message;

import com.summit.core.conversation.api.ChatResponseEntity;
import com.summit.core.conversation.api.ToolCallRequest;
import com.summit.core.conversation.message.*;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.*;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.ChatResponseMetadata;
import dev.langchain4j.model.output.TokenUsage;

import java.util.ArrayList;
import java.util.List;

/**
 * Provider-neutral codec between core {@link Message} and langchain4j {@link ChatMessage}.
 */
public class MessageCodecAdapter implements MessageCodec<ChatMessage, ChatResponse> {

    @Override
    public ChatMessage toFramework(Message message) {

        if (message instanceof SystemMessageEntity systemMessage) {
            return SystemMessage.from(systemMessage.getText());
        }

        if (message instanceof UserMessageEntity userMessage) {
            return UserMessage.from(ContentCodecAdapter.toFramework(userMessage.getContent()));
        }

        if (message instanceof AiMessageEntity aiMessage) {
            AiMessage.Builder builder = AiMessage.builder();
            if (aiMessage.getAttributes() != null) {
                builder.attributes(aiMessage.getAttributes());
            }
            if (aiMessage.getText() != null) {
                builder.text(aiMessage.getText());
            }
            if (aiMessage.getThinking() != null) {
                builder.thinking(aiMessage.getThinking());
            }
            if (aiMessage.getToolCalls() != null && !aiMessage.getToolCalls().isEmpty()) {
                builder.toolExecutionRequests(toLangchain4jRequests(aiMessage.getToolCalls()));
            }
            return builder.build();
        }

        if (message instanceof ToolMessageEntity toolMessage) {
            return ToolExecutionResultMessage.builder()
                    .id(String.valueOf(toolMessage.getId()))
                    .toolName(toolMessage.getName())
                    .isError(toolMessage.isError())
                    .text(toolMessage.text())
                    .build();
        }

        throw new IllegalArgumentException("unsupported message type: " + message.getClass().getName());
    }

    @Override
    public List<ChatMessage> toFramework(List<? extends Message> messages) {
        List<ChatMessage> result = new ArrayList<>();
        if (messages == null) {
            return result;
        }
        for (Message message : messages) {
            result.add(toFramework(message));
        }
        return result;
    }

    @Override
    public ChatResponseEntity toChatResponseEntity(ChatResponse response) {
        AiMessage aiMessage = response.aiMessage();
        List<ToolCallRequest> toolCalls = new ArrayList<>();

        for (int i = 0; i < aiMessage.toolExecutionRequests().size(); i++) {
            ToolExecutionRequest request = aiMessage.toolExecutionRequests().get(i);
            toolCalls.add(ToolCallRequest.builder()
                    .id(request.id())
                    .name(request.name())
                    .requestIndex(i)
                    .arguments(request.arguments())
                    .build());
        }


        AiMessageEntity aiMessageEntity = AiMessageEntity.builder()
                .text(aiMessage.text())
                .thinking(aiMessage.thinking())
                .attributes(aiMessage.attributes())
                .toolCalls(toolCalls)
                .build();

        TokenUsageEntity tokenUsageEntity = toTokenUsageEntity(response.tokenUsage());

        ChatResponseMetadata metadata = response.metadata();


        ChatResponseEntity.Meta meta = ChatResponseEntity.Meta.builder()
                .id(metadata.id())
                .finishReason(metadata.finishReason() == null ? ChatResponseEntity.FinishReason.OTHER : ChatResponseEntity.FinishReason.valueOf(metadata.finishReason().name()))
                .modelName(metadata.modelName())
                .build();

        return ChatResponseEntity.builder()
                .aiMessageEntity(aiMessageEntity)
                .tokenUsage(tokenUsageEntity)
                .meta(meta)
                .build();
    }

    private List<ToolExecutionRequest> toLangchain4jRequests(List<ToolCallRequest> toolCalls) {
        List<ToolExecutionRequest> requests = new ArrayList<>();
        for (ToolCallRequest toolCall : toolCalls) {
            requests.add(ToolExecutionRequest.builder()
                    .id(toolCall.id())
                    .name(toolCall.name())
                    .arguments(toolCall.arguments())
                    .build());
        }
        return requests;
    }

    /**
     * Provider codecs override this conversion to preserve their usage details.
     */
    protected TokenUsageEntity toTokenUsageEntity(TokenUsage usage) {
        if (usage == null) {
            return TokenUsageEntity.empty();
        }
        return TokenUsageEntity.of(orZero(usage.totalTokenCount()),
                orZero(usage.inputTokenCount()), orZero(usage.outputTokenCount()));
    }

    protected static int orZero(Integer value) {
        return value == null ? 0 : value;
    }
}
