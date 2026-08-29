package com.summit.harnesscore.conversation;

import lombok.NonNull;

import java.io.Serializable;
import java.util.Collection;
import java.util.Optional;

public interface ConversationStore {

    Optional<ConversationEntity> get(@NonNull Serializable sessionId);


    void save(@NonNull Serializable sessionId, @NonNull ConversationEntity conversation);

    default Optional<ConversationEntity> removeAndReturn(@NonNull Serializable sessionId){
        return Optional.empty();
    };

    void remove(@NonNull Serializable sessionId);

    void clear();

    Collection<ConversationEntity> list();
}
