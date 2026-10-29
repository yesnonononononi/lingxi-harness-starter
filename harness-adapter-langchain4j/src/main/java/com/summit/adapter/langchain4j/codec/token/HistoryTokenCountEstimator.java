package com.summit.adapter.langchain4j.codec.token;

import com.fasterxml.jackson.core.JsonProcessingException;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.openai.OpenAiTokenCountEstimator;

import java.util.List;

/** Failed tool arguments remain valid history even when they cannot be decoded as JSON. */
final class HistoryTokenCountEstimator extends OpenAiTokenCountEstimator {

    HistoryTokenCountEstimator(String modelName) {
        super(modelName);
    }

    @Override
    public int estimateTokenCountInMessage(ChatMessage message) {
        try {
            return super.estimateTokenCountInMessage(message);
        } catch (RuntimeException failure) {
            if (!(message instanceof AiMessage aiMessage)
                    || aiMessage.toolExecutionRequests().size() < 2
                    || !isArgumentDecodingFailure(failure)) {
                throw failure;
            }

            // The upstream single-call path counts raw arguments without decoding them. Split only
            // this estimation view; the original history and provider request must stay untouched.
            int tokens = 0;
            boolean first = true;
            for (ToolExecutionRequest request : aiMessage.toolExecutionRequests()) {
                AiMessage singleCall = aiMessage.toBuilder()
                        .text(first ? aiMessage.text() : null)
                        .thinking(first ? aiMessage.thinking() : null)
                        .toolExecutionRequests(List.of(request))
                        .build();
                tokens += super.estimateTokenCountInMessage(singleCall);
                first = false;
            }
            return tokens;
        }
    }

    private boolean isArgumentDecodingFailure(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof JsonProcessingException) {
                return true;
            }
        }
        return false;
    }
}
