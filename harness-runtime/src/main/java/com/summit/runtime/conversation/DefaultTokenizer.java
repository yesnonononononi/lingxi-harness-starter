package com.summit.runtime.conversation;

import com.summit.core.adapter.TokenEstimator;
import com.summit.core.compact.Tokenizer;
import com.summit.core.conversation.message.AiMessageEntity;
import com.summit.core.conversation.message.Message;
import com.summit.core.conversation.message.SystemMessageEntity;
import com.summit.core.conversation.message.ToolMessageEntity;
import com.summit.core.conversation.message.UserMessageEntity;

import java.util.List;

/**
 * Default Tokenizer: prefers the injected {@link TokenEstimator} (provided by an adapter module,
 * e.g. OpenAI BPE based) for accurate estimation; falls back to a simple chars/3 estimate when no estimator is available.
 */
public class DefaultTokenizer implements Tokenizer {

    private final TokenEstimator tokenEstimator;

    public DefaultTokenizer() {
        this(null);
    }

    public DefaultTokenizer(TokenEstimator tokenEstimator) {
        this.tokenEstimator = tokenEstimator;
    }

    @Override
    public int count(List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return 0;
        }
        if (tokenEstimator != null) {
            return tokenEstimator.estimateMessages(messages);
        }
        int res = 0;
        for (Message message : messages) {
            res += estimateTokenCountFallback(message);
        }
        return res;
    }

    private int estimateTokenCountFallback(Message message) {
        if (message instanceof SystemMessageEntity systemMessage) {
            return systemMessage.text().length() / 3;
        } else if (message instanceof UserMessageEntity userMessage) {
            return userMessage.text().length() / 3;
        } else if (message instanceof AiMessageEntity aiMessage) {
            int res = 0;
            if (aiMessage.getToolCalls() != null) {
                for (var toolCallRequest : aiMessage.getToolCalls()) {
                    res += (toolCallRequest.arguments() == null ? 0 : toolCallRequest.arguments().length()) / 3;
                    res += (toolCallRequest.name() == null ? 0 : toolCallRequest.name().length()) / 3;
                }
            }
            res += (aiMessage.text() == null ? 0 : aiMessage.text().length()) / 3;
            res += (aiMessage.getThinking() == null ? 0 : aiMessage.getThinking().length()) / 3;
            return res;
        } else if (message instanceof ToolMessageEntity toolMessage) {
            return toolMessage.text().length() / 3;
        }
        return 0;
    }

    @Override
    public String truncate(String output, int maxOutput) {
        if (output == null || output.isEmpty()) {
            return output;
        }

        int totalTokens = tokenEstimator != null
                ? tokenEstimator.estimateText(output)
                : output.length() / 3;
        if (totalTokens <= maxOutput) {
            return output;
        }
        int tokenBudget = Math.max(maxOutput, 1);

        int headTokens = tokenBudget / 2;
        int tailTokens = tokenBudget - headTokens;


        double charsPerToken = (double) output.length() / Math.max(totalTokens, 1);
        int headChars = Math.min(output.length(), (int) (headTokens * charsPerToken));
        int tailChars = Math.min(output.length() - headChars, (int) (tailTokens * charsPerToken));

        return output.substring(0, headChars)
                + "\n...[OUTPUT_TRUNCATED]...\n"
                + output.substring(output.length() - tailChars);
    }
}
