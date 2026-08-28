package com.summit.harnesscore.runtime;

import com.summit.harnesscore.agent.Execution;
import com.summit.harnesscore.conversation.ConversationManager;
import com.summit.harnesscore.compact.ContextSqueezeRequest;

public interface RuntimeExecutionPolicy {
    boolean shouldContinue(Execution execution, ConversationManager conversationManager);

    ContextSqueezeRequest shouldSqueezeContext(ConversationManager conversationManager, Execution execution);
}
