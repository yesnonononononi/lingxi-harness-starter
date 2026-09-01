package com.summit.core.conversation;

import com.summit.core.conversation.message.Message;
import com.summit.core.conversation.message.SystemMessageEntity;
import com.summit.core.conversation.message.TokenUsageEntity;
import com.summit.core.runtime.Workspace;
import lombok.Builder;
import lombok.NonNull;
import java.io.Serializable;
import java.util.LinkedList;
import java.util.List;
@Builder
public record ConversationEntity(
        Serializable sessionId, String sessionName, List<Message> messages, TokenUsageEntity tokenUsageEntity, SystemMessageEntity SystemMessageEntity, Workspace workspace
        ) {
    public static ConversationEntity empty(String sessionName,Workspace workspace,SystemMessageEntity SystemMessageEntity,Serializable sessionId,List<Message> messages){
        return ConversationEntity.builder()
                .sessionName(sessionName)
                .messages(messages)
                .tokenUsageEntity(TokenUsageEntity.empty())
                .SystemMessageEntity(SystemMessageEntity)
                .workspace(workspace)
                .sessionId(sessionId)
                .build();
    }
    public static ConversationEntity empty(String sessionName,Workspace workspace,SystemMessageEntity SystemMessageEntity,Serializable sessionId) {
        return empty(sessionName, workspace, SystemMessageEntity, sessionId, new LinkedList<>());
    }

    public @NonNull ConversationEntity withSessionId(@NonNull Serializable sessionId) {
        return new ConversationEntity(sessionId, sessionName, messages, tokenUsageEntity, SystemMessageEntity, workspace);
    }

    public ConversationEntity withSessionName(String sessionName) {
        return new ConversationEntity(sessionId, sessionName, messages, tokenUsageEntity, SystemMessageEntity, workspace);
    }
}
