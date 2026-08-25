package com.summit.runtime.conversation;

import com.summit.harnesscore.compact.Tokenizer;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.*;

import java.util.List;
import java.util.Objects;

public class DefaultTokenizer implements Tokenizer {

    @Override
    public int count(List<ChatMessage> messages) {
        int res = 0;
        for (ChatMessage message : messages) {
            if (message instanceof SystemMessage systemMessage) {
                res += countSystemMessageTokens(systemMessage);
            }
            if (message instanceof UserMessage userMessage) {
                res += countUserMessageTokens(userMessage);
            }
            if (message instanceof AiMessage aiMessage) {
                res += countAiMessageTokens(aiMessage);
            }
            if (message instanceof ToolExecutionResultMessage toolExecutionResultMessage) {
                res += countToolExecutionResultMessageTokens(toolExecutionResultMessage);
            }
        }
        return convertCharToToken(res);
    }




    private int countToolExecutionResultMessageTokens(ToolExecutionResultMessage toolExecutionResultMessage) {
        return toolExecutionResultMessage.text().length();
    }

    private int countAiMessageTokens(AiMessage aiMessage) {
        int res = 0;
        for (ToolExecutionRequest toolExecutionRequest : aiMessage.toolExecutionRequests()) {
            res += Objects.requireNonNullElse(toolExecutionRequest.arguments(), "").length();
            res += Objects.requireNonNullElse(toolExecutionRequest.name(), "").length();
        }
        res += Objects.requireNonNullElse(aiMessage.text(), "").length() + Objects.requireNonNullElse(aiMessage.thinking(), "").length();
        return res;
    }

    private int countUserMessageTokens(UserMessage userMessage) {
        int res = 0;
        res += Objects.requireNonNullElse(userMessage.singleText(), "").length();
        return res;
    }

    private int countSystemMessageTokens(SystemMessage systemMessage) {
        return Objects.requireNonNullElse(systemMessage.text(), "").length();
    }

    @Override
    public String truncate(String output, int maxOutput) {
        if (output == null || output.isEmpty()) {
            return output;
        }

        int size = convertCharToToken(output.length());
        if (size <= maxOutput) {
            return output;
        }
        int tokenBudget = Math.max(maxOutput, 1);

        int headTokens = tokenBudget / 2;
        int tailTokens = tokenBudget - headTokens;

        // token -> chars can conveniently substring
        int headChars = Math.min(output.length(), headTokens * 3);
        int tailChars = Math.min(output.length() - headChars, tailTokens * 3);


        return output.substring(0, headChars)
                + "\n...[OUTPUT_TRUNCATED]...\n"
                + output.substring(output.length() - tailChars);
    }

    public int convertCharToToken(int charCount) {
        return charCount / 3;
    }
}
