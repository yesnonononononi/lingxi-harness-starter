package com.summit.harnesscore.conversation;

import lombok.NonNull;

import java.io.Serializable;
import java.util.Collection;
import java.util.Optional;

public interface ConversationStore {
    Serializable generateId();

    Optional<ConversationEntity> get(@NonNull Serializable sessionId);

    Serializable save(@NonNull ConversationEntity conversationManager);

    void save(@NonNull Serializable sessionId, @NonNull ConversationEntity conversationManager);

    ConversationEntity remove(@NonNull Serializable sessionId);

    void clear();

    Collection<ConversationEntity> getAll();
}
