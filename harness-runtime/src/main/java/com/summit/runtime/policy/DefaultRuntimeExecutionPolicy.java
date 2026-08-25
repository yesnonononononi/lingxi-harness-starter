package com.summit.runtime.policy;

import com.summit.harnesscore.agent.Execution;
import com.summit.harnesscore.compact.Tokenizer;
import com.summit.harnesscore.conversation.ConversationManager;

import com.summit.harnesscore.compact.ContextSqueezeRequest;
import com.summit.harnesscore.runtime.RuntimeExecutionPolicy;
import com.summit.runtime.agent.AgentConfig;
import dev.langchain4j.model.output.TokenUsage;
import lombok.AllArgsConstructor;

@AllArgsConstructor
public class DefaultRuntimeExecutionPolicy implements RuntimeExecutionPolicy {
    private final AgentConfig agentConfig ;
    private final Tokenizer tokenizer;
    @Override
    public boolean shouldContinue(Execution execution, ConversationManager conversationManager) {
        Integer maxTokens = agentConfig.maxTokens();
        if (maxTokens == null) return true;
        int accumulatedTokens = conversationManager.tokenUsage().totalTokenCount();
        int currentContextTokens = tokenizer.count(conversationManager.messages());
        return accumulatedTokens < maxTokens && currentContextTokens < maxTokens;
   }

    @Override
    public ContextSqueezeRequest shouldSqueezeContext(ConversationManager conversationManager, Execution execution) {
        int currentTokens = tokenizer.count(conversationManager.messages());
        Double v = agentConfig.squeezeThreshold();
        Integer maxTokens = agentConfig.maxTokens();
        double expectT = maxTokens * (v > 1.0 ? 1.0 : v);
        return ContextSqueezeRequest.builder()
                .shouldSqueeze(currentTokens > expectT)
                .expectTokens((int) expectT)
                .build();
    }
}
