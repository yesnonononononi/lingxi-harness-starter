package com.summit.core.runtime;

import com.summit.core.agent.Execution;
import com.summit.core.conversation.ConversationManager;
import com.summit.core.compact.ContextSqueezeRequest;

public interface RuntimeExecutionPolicy {
    boolean shouldContinue(Execution execution, ConversationManager conversationManager);

    ContextSqueezeRequest shouldSqueezeContext(ConversationManager conversationManager, Execution execution);
}
