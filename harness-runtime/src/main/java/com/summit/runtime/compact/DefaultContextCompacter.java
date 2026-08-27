package com.summit.runtime.compact;

import com.summit.harnesscore.compact.ContextCompacter;
import com.summit.harnesscore.compact.Tokenizer;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.*;

@Slf4j
@RequiredArgsConstructor
public class DefaultContextCompacter implements ContextCompacter {

    private static final int DEFAULT_ATTEMPT_NUM = 10;
    private final Tokenizer tokenizer;

    public void compact( Integer expectedTokens, Integer attemptNum, List<ChatMessage> messages) {
        int originalTokens = this.tokenizer.count(messages);
        int maxAttempts = Math.max(
                Objects.requireNonNullElse(attemptNum, DEFAULT_ATTEMPT_NUM),
                1
        );

        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            // 每次重新计算当前上下文 token，避免传入的 currentTokens 与真实值不一致
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

    public void compact( Integer expectedTokens, List<ChatMessage> messages) {
        this.compact(expectedTokens, DEFAULT_ATTEMPT_NUM, messages);
    }

    /**
     * 成对删除最旧的一轮工具交互（AiMessage 及其紧随的 ToolExecutionResultMessage），
     * 保证 tool call / tool result 配对完整，避免模型 API 因缺失 tool result 报错。
     * 返回本次删除的消息 token 数；无可删时返回 0。
     */
    private int removeOldestToolRound(List<ChatMessage> messages) {
        for (int i = 1; i < messages.size(); i++) {
            ChatMessage msg = messages.get(i);
            if (msg instanceof AiMessage ai) {
                int removedTokens = this.tokenizer.count(ai);
                messages.remove(i);
                while (i < messages.size() && messages.get(i) instanceof ToolExecutionResultMessage) {
                    removedTokens += this.tokenizer.count(messages.get(i));
                    messages.remove(i);
                }
                return removedTokens;
            }
        }
        return 0;
    }
}
