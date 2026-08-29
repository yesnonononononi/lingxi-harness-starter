package com.summit.harnesscore.conversation;

import com.summit.harnesscore.conversation.message.Message;
import com.summit.harnesscore.conversation.message.SystemMessageEntity;
import com.summit.harnesscore.conversation.message.TokenUsageEntity;
import com.summit.harnesscore.runtime.Workspace;
import lombok.Builder;
import lombok.NonNull;
import java.io.Serializable;
import java.util.LinkedList;
import java.util.List;
@Builder
public record ConversationEntity(
        Serializable sessionId, List<Message> messages, TokenUsageEntity tokenUsageEntity, SystemMessageEntity SystemMessageEntity, Workspace workspace
        ) {
    public static ConversationEntity empty(Workspace workspace,SystemMessageEntity SystemMessageEntity,Serializable sessionId,List<Message> messages){
        return ConversationEntity.builder()
                .messages(messages)
                .tokenUsageEntity(TokenUsageEntity.empty())
                .SystemMessageEntity(SystemMessageEntity)
                .workspace(workspace)
                .sessionId(sessionId)
                .build();
    }
    public static ConversationEntity empty(Workspace workspace,SystemMessageEntity SystemMessageEntity,Serializable sessionId) {
        return empty(workspace, SystemMessageEntity, sessionId, new LinkedList<>());
    }

    public @NonNull ConversationEntity withSessionId(@NonNull Serializable sessionId) {
        return new ConversationEntity(sessionId, messages, tokenUsageEntity, SystemMessageEntity, workspace);
    }
}
