package com.summit.runtime.conversation;

import com.summit.core.conversation.ConversationEntity;
import com.summit.core.conversation.ConversationStore;
import lombok.NonNull;

import java.io.Serializable;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public class DefaultConversationStore implements ConversationStore {
    private final Map<Serializable, ConversationEntity> managerStore = new ConcurrentHashMap<>();



    @Override
    public  Optional<ConversationEntity> get(@NonNull Serializable sessionId) {
       return Optional.ofNullable(managerStore.get(sessionId));
    }




    @Override
    public void save(@NonNull Serializable sessionId, @NonNull ConversationEntity conversationEntity) {
        if(conversationEntity.sessionId() == null){
            conversationEntity = conversationEntity.withSessionId(sessionId);
        }
        this.managerStore.put(sessionId, conversationEntity);
    }


    @Override
    public Optional<ConversationEntity> removeAndReturn(@NonNull Serializable sessionId) {
        return Optional.ofNullable(managerStore.remove(sessionId));
    }


}
