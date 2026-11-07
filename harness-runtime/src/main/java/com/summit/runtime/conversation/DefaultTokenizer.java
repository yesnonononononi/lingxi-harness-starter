package com.summit.runtime.conversation;

import com.summit.core.adapter.TokenEstimator;
import com.summit.core.compact.ContextUsageMetric;
import com.summit.core.compact.Tokenizer;
import com.summit.core.conversation.message.*;
import com.summit.core.tool.ToolDefinition;

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
        return count(messages, List.of());
    }

    @Override
    public int countText(String text) {
        if(text == null || text.isEmpty())return 0;
        return this.tokenEstimator == null ? this.estimateTokenCountFallbackOfText(text) : this.tokenEstimator.estimateText(text);
    }

    @Override
    public int count(List<Message> messages, List<ToolDefinition<?>> tools) {
        boolean emptyHistory = messages == null || messages.isEmpty();
        if (emptyHistory && (tools == null || tools.isEmpty())) return 0;
        if (tokenEstimator != null) {
            return tokenEstimator.estimateMessages(emptyHistory ? List.of() : messages, tools);
        }
        if (emptyHistory) return 0;
        int res = 0;
        for (Message message : messages) {
            res += estimateTokenCountFallback(message);
        }
        return res;
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

    @Override
    public double calcCurrentTokenRatio(List<Message> messages,List<ToolDefinition<?>> tools, int maxTokens) {
        int currentTokens = this.count(messages,tools);
        return currentTokens / (double) maxTokens;
    }

    @Override
    public ContextUsageMetric usage(List<Message> messages,List<ToolDefinition<?>> tools, Integer maxTokens) {
        if (maxTokens == null || maxTokens <= 0) {
            return null;
        }
        int tokenCount = count(messages,tools);
        return new ContextUsageMetric(
                tokenCount,
                maxTokens,
                tokenCount / (double) maxTokens);
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

    private int estimateTokenCountFallbackOfText(String text){
        if(text == null || text.isEmpty())return 0;
        return text.length() / 3;
    }

}
