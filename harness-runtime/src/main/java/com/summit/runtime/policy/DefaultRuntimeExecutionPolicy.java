package com.summit.runtime.policy;

import com.summit.core.agent.Execution;
import com.summit.core.compact.Tokenizer;
import com.summit.core.conversation.ConversationManager;

import com.summit.core.compact.ContextSqueezeRequest;
import com.summit.core.runtime.RuntimeExecutionPolicy;
import com.summit.runtime.agent.AgentConfig;
import lombok.AllArgsConstructor;

@AllArgsConstructor
public class DefaultRuntimeExecutionPolicy implements RuntimeExecutionPolicy {
    private final AgentConfig agentConfig ;
    private final Tokenizer tokenizer;
    @Override
    public boolean shouldContinue(Execution execution, ConversationManager conversationManager) {
        Integer maxTokens = agentConfig.maxTokens();
        if (maxTokens == null) return true;
        int accumulatedTokens = conversationManager.tokenUsage(execution.getSessionId()).getTotalTokens();
        int currentContextTokens = tokenizer.count(conversationManager.messages(execution.getSessionId()));
        return currentContextTokens < maxTokens;
   }

    @Override
    public ContextSqueezeRequest shouldSqueezeContext(ConversationManager conversationManager, Execution execution) {
        int currentTokens = tokenizer.count(conversationManager.messages(execution.getSessionId()));
        Double v = agentConfig.squeezeThreshold();
        Integer maxTokens = agentConfig.maxTokens();
        double expectT = maxTokens * (v > 1.0 ? 1.0 : v);
        return ContextSqueezeRequest.builder()
                .shouldSqueeze(currentTokens > expectT)
                .expectTokens((int) expectT)
                .build();
    }
}
