package com.summit.adapter.langchain4j.codec;

import com.summit.core.conversation.api.ChatResponseEntity;
import com.summit.core.conversation.api.ToolCallRequest;
import com.summit.core.conversation.message.AiMessageEntity;
import com.summit.core.conversation.message.Message;
import com.summit.core.conversation.message.SystemMessageEntity;
import com.summit.core.conversation.message.TokenUsageEntity;
import com.summit.core.conversation.message.ToolMessageEntity;
import com.summit.core.conversation.message.UserMessageEntity;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.ChatResponseMetadata;

import java.util.ArrayList;
import java.util.List;

/**
 * Bi-directional codec between core {@link Message} and langchain4j {@link ChatMessage}.
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
            return ToolExecutionResultMessage.from(
                    String.valueOf(toolMessage.getId()),
                    toolMessage.getName(),
                    toolMessage.getText()
            );
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
                .toolCalls(toolCalls)
                .build();

        TokenUsageEntity tokenUsage = TokenUsageEntity.empty();
        if (response.tokenUsage() != null) {
            tokenUsage = TokenUsageEntity.of(
                    orZero(response.tokenUsage().totalTokenCount()),
                    orZero(response.tokenUsage().inputTokenCount()),
                    orZero(response.tokenUsage().outputTokenCount())
            );
        }

        ChatResponseMetadata metadata = response.metadata();


        ChatResponseEntity.Meta meta = ChatResponseEntity.Meta.builder()
                .id(metadata.id())
                .finishReason(metadata.finishReason() == null ? ChatResponseEntity.FinishReason.OTHER : ChatResponseEntity.FinishReason.valueOf(metadata.finishReason().name()))
                .modelName(metadata.modelName())
                .build();

        return ChatResponseEntity.builder()
                .aiMessageEntity(aiMessageEntity)
                .tokenUsage(tokenUsage)
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

    private static int orZero(Integer value) {
        return value == null ? 0 : value;
    }
}
