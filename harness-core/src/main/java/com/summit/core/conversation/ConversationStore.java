package com.summit.core.conversation;

import com.summit.core.compact.ContextUsageMetric;
import com.summit.core.conversation.message.Message;
import lombok.NonNull;

import java.io.Serializable;
import java.util.List;
import java.util.Optional;

/**
 * Persistence SPI for conversations. Write access is owned by
 * {@link ConversationManager}; callers may depend on the store directly for
 * reads.
 */
public interface ConversationStore {

    Optional<ConversationEntity> get(@NonNull Serializable sessionId);


    void save(@NonNull Serializable sessionId, @NonNull ConversationEntity conversation);

    default Optional<ConversationEntity> removeAndReturn(@NonNull Serializable sessionId){
        return Optional.empty();
    };



}
