package com.summit.runtime.compact;

import com.summit.core.compact.ContextCompacter;
import com.summit.core.compact.Tokenizer;
import com.summit.core.conversation.message.AiMessageEntity;
import com.summit.core.conversation.message.Message;
import com.summit.core.conversation.message.ToolMessageEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.*;

@Slf4j
@RequiredArgsConstructor
public class DefaultContextCompacter implements ContextCompacter {

    private static final int DEFAULT_ATTEMPT_NUM = 10;
    private final Tokenizer tokenizer;

    public void compact( Integer expectedTokens, Integer attemptNum, List<Message> messages) {
        int originalTokens = this.tokenizer.count(messages);
        int maxAttempts = Math.max(
                Objects.requireNonNullElse(attemptNum, DEFAULT_ATTEMPT_NUM),
                1
        );

        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            // recompute the current context token count on every attempt, in case the passed-in value is stale
            if (originalTokens <= expectedTokens) {
                break;
            }
            try {
                int removed = removeOldestToolRound(messages);
                if (removed <= 0) {
                    break;
                }
                originalTokens -= removed;
            } catch (Exception e) {
                log.error("【Context Compacter-local】Error occurred while squeezing messages by age, current retry: {}", attempt, e);
            }
        }
        log.info("【context-squeeze】context squeezed from {} to {} tokens (expected {})", originalTokens, this.tokenizer.count(messages), expectedTokens);
    }

    public void compact( Integer expectedTokens, List<Message> messages) {
        this.compact(expectedTokens, DEFAULT_ATTEMPT_NUM, messages);
    }

    /**
     * Removes the oldest tool interaction round in pairs (an AiMessage plus its following ToolMessageEntity),
     * keeping tool call / tool result pairs complete so the model API does not fail on missing tool results.
     * Returns the token count removed in this pass; 0 when nothing can be removed.
     */
    private int removeOldestToolRound(List<Message> messages) {
        for (int i = 1; i < messages.size(); i++) {
            Message msg = messages.get(i);
            if (msg instanceof AiMessageEntity ai) {
                int removedTokens = this.tokenizer.count(ai);
                messages.remove(i);
                while (i < messages.size() && messages.get(i) instanceof ToolMessageEntity) {
                    removedTokens += this.tokenizer.count(messages.get(i));
                    messages.remove(i);
                }
                return removedTokens;
            }
        }
        return 0;
    }
}
