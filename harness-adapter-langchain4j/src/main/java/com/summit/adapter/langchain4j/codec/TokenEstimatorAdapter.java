package com.summit.adapter.langchain4j.codec;

import com.summit.core.adapter.TokenEstimator;
import com.summit.core.conversation.message.Message;
import com.summit.core.conversation.message.UserMessageEntity;
import com.summit.core.conversation.message.content.Content;
import com.summit.core.conversation.message.content.TextContent;
import dev.langchain4j.model.TokenCountEstimator;
import dev.langchain4j.model.openai.OpenAiTokenCountEstimator;

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
        this(new OpenAiTokenCountEstimator(modelName));
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
        messages = filterUnSupportedMessage(messages);
        List<dev.langchain4j.data.message.ChatMessage> chatMessages = new MessageCodecAdapter().toFramework(messages);
        if (chatMessages.isEmpty()) {
            return 0;
        }



        return delegate.estimateTokenCountInMessages(chatMessages);
    }

    private List<Message> filterUnSupportedMessage(List<Message> messages) {
        return messages.stream().map(m -> {
            if (m instanceof UserMessageEntity um) {
                List<Content> newContent = um.getContent().stream().filter(c -> c instanceof TextContent).toList();
                return new UserMessageEntity(newContent);
            }
            return m;
        }).toList();
    }
}
