package com.summit.runtime.policy;

import com.summit.harnesscore.agent.Execution;
import com.summit.harnesscore.conversation.ConversationManager;

import com.summit.harnesscore.compact.ContextSqueezeRequest;
import com.summit.harnesscore.runtime.RuntimeExecutionPolicy;
import com.summit.runtime.agent.AgentConfig;
import dev.langchain4j.model.output.TokenUsage;
import lombok.AllArgsConstructor;

@AllArgsConstructor
public class DefaultRuntimeExecutionPolicy implements RuntimeExecutionPolicy {
    private final AgentConfig agentConfig ;
    @Override
    public boolean shouldContinue(Execution execution, ConversationManager conversationManager) {
        TokenUsage tokenUsage = conversationManager.tokenUsage();
        Integer maxTokens =agentConfig.maxTokens();
        Integer count = tokenUsage.totalTokenCount();
      return count < maxTokens;
   }

    @Override
    public ContextSqueezeRequest shouldSqueezeContext(ConversationManager conversationManager, Execution execution) {
        Integer tokenUsage = conversationManager.tokenUsage().totalTokenCount();
        Double v = agentConfig.squeezeThreshold();
        Integer maxTokens = agentConfig.maxTokens();
        double expectT = maxTokens * (v > 1.0 ? 1.0 : v);
        return ContextSqueezeRequest.builder()
                .shouldSqueeze(tokenUsage > expectT)
                .expectTokens((int) (expectT * agentConfig.squeezeThreshold()))
                .build();
    }
}
