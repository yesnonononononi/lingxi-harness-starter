package com.summit.runtime.conversation;

import com.summit.harnesscore.conversation.ConversationEntity;
import com.summit.harnesscore.conversation.ConversationStore;
import lombok.NonNull;

import java.io.Serializable;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class DefaultConversationStore implements ConversationStore {
    private final Map<Serializable, ConversationEntity> managerStore = new ConcurrentHashMap<>();


    @Override
    public Serializable generateId() {
        return UUID.randomUUID();
    }

    @Override
    public  Optional<ConversationEntity> get(@NonNull Serializable sessionId) {
       return Optional.ofNullable(managerStore.get(sessionId));
    }


    @Override
    public Serializable save(@NonNull ConversationEntity conversationEntity) {
        Serializable id = this.generateId();
        managerStore.put(id,conversationEntity);
        return id;
    }

    @Override
    public void save(@NonNull Serializable sessionId, @NonNull ConversationEntity conversationEntity) {
        this.managerStore.put(sessionId, conversationEntity);
    }


    @Override
    public ConversationEntity  remove(@NonNull Serializable sessionId) {
        return managerStore.remove(sessionId);
    }

    @Override
    public void clear() {
        this.managerStore.clear();
    }

    @Override
    public Collection<ConversationEntity> getAll() {
        return this.managerStore.values().stream().toList();
    }


}
