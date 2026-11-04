package com.summit.adapter.langchain4j.codec.token;

import com.summit.core.conversation.message.AiMessageEntity;
import com.summit.core.conversation.message.SystemMessageEntity;
import com.summit.core.conversation.message.ToolMessageEntity;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import com.summit.core.adapter.TokenEstimator;
import com.summit.core.conversation.message.Message;
import com.summit.core.conversation.message.UserMessageEntity;
import com.summit.core.conversation.message.content.TextContent;
import dev.langchain4j.model.TokenCountEstimator;

import java.util.List;

/**
 * Token estimation backed by the langchain4j {@link TokenCountEstimator} (OpenAI BPE encoding).
 */
public class TokenEstimatorAdapter implements TokenEstimator {

    private final TokenCountEstimator delegate;

    public TokenEstimatorAdapter(TokenCountEstimator delegate) {
        this.delegate = delegate;
    }

    public TokenEstimatorAdapter(String modelName) {
        this(new HistoryTokenCountEstimator(modelName));
    }

    @Override
    public int estimateText(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return delegate.estimateTokenCountInText(text);
    }

    @Override
    public int estimateMessages(List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return 0;
        }
        return delegate.estimateTokenCountInMessages(messages.stream().map(TokenEstimatorAdapter::toTokenMessage).toList());
    }

    static ChatMessage toTokenMessage(Message message) {
        if (message instanceof SystemMessageEntity system) return SystemMessage.from(system.getText());
        if (message instanceof UserMessageEntity user) {
            List<dev.langchain4j.data.message.Content> text = user.getContent().stream()
                    .filter(TextContent.class::isInstance).map(TextContent.class::cast)
                    .map(part -> (dev.langchain4j.data.message.Content) dev.langchain4j.data.message.TextContent.from(part.getText()))
                    .toList();
            return UserMessage.from(text);
        }
        if (message instanceof AiMessageEntity assistant) {
            String text = assistant.getText();
            if ((text == null || text.isBlank()) && assistant.getImages() != null && !assistant.getImages().isEmpty()) {
                text = "[Assistant generated " + assistant.getImages().size() + " image(s)]";
            }
            AiMessage.Builder builder = AiMessage.builder().text(text).thinking(assistant.getThinking());
            if (assistant.getToolCalls() != null && !assistant.getToolCalls().isEmpty()) {
                builder.toolExecutionRequests(assistant.getToolCalls().stream()
                        .map(call -> ToolExecutionRequest.builder().id(call.id()).name(call.name()).arguments(call.arguments()).build())
                        .toList());
            }
            return builder.build();
        }
        if (message instanceof ToolMessageEntity tool) {
            return ToolExecutionResultMessage.builder().id(String.valueOf(tool.getId())).toolName(tool.getName())
                    .isError(tool.isError()).text(tool.text()).build();
        }
        throw new IllegalArgumentException("Unsupported token estimation message type: " + message.getClass().getName());
    }
}
