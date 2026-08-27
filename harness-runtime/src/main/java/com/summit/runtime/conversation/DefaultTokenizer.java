package com.summit.runtime.conversation;

import com.summit.harnesscore.compact.Tokenizer;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.*;
import dev.langchain4j.model.TokenCountEstimator;

import java.util.List;

/**
 * 默认 Tokenizer：优先使用 langchain4j 的 {@link TokenCountEstimator}（如 OpenAI BPE 编码）精确估算；
 * 未提供（自定义 Provider 无估算器）时退化为「字符数/3」的简单估算兜底。
 */
public class DefaultTokenizer implements Tokenizer {

    private final TokenCountEstimator tokenCountEstimator;

    public DefaultTokenizer() {
        this(null);
    }

    public DefaultTokenizer(TokenCountEstimator tokenCountEstimator) {
        this.tokenCountEstimator = tokenCountEstimator;
    }

    @Override
    public int count(List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return 0;
        }
        if (tokenCountEstimator != null) {
            return tokenCountEstimator.estimateTokenCountInMessages(messages);
        }
        int res = 0;
        for (ChatMessage message : messages) {
            res += estimateTokenCountFallback(message);
        }
        return res;
    }

    private int estimateTokenCountFallback(ChatMessage message) {
        if (message instanceof SystemMessage systemMessage) {
            return systemMessage.text().length() / 3;
        } else if (message instanceof UserMessage userMessage) {
            return userMessage.singleText().length() / 3;
        } else if (message instanceof AiMessage aiMessage) {
            int res = 0;
            for (ToolExecutionRequest toolExecutionRequest : aiMessage.toolExecutionRequests()) {
                res += (toolExecutionRequest.arguments() == null ? 0 : toolExecutionRequest.arguments().length()) / 3;
                res += (toolExecutionRequest.name() == null ? 0 : toolExecutionRequest.name().length()) / 3;
            }
            res += (aiMessage.text() == null ? 0 : aiMessage.text().length()) / 3;
            res += (aiMessage.thinking() == null ? 0 : aiMessage.thinking().length()) / 3;
            return res;
        } else if (message instanceof ToolExecutionResultMessage toolExecutionResultMessage) {
            return toolExecutionResultMessage.text().length() / 3;
        }
        return 0;
    }

    @Override
    public String truncate(String output, int maxOutput) {
        if (output == null || output.isEmpty()) {
            return output;
        }

        int totalTokens = tokenCountEstimator != null
                ? tokenCountEstimator.estimateTokenCountInText(output)
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
