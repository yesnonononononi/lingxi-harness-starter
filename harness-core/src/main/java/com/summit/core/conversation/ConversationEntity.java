package com.summit.core.conversation;

import com.summit.core.conversation.message.Message;
import com.summit.core.conversation.message.SystemMessageEntity;
import com.summit.core.conversation.message.TokenUsageEntity;
import com.summit.core.workspace.WorkspaceSpec;
import lombok.Builder;
import lombok.NonNull;
import java.io.Serializable;
import java.util.LinkedList;
import java.util.List;
@Builder
public record ConversationEntity(
        Serializable sessionId, String sessionName, List<Message> messages,
        TokenUsageEntity tokenUsageEntity, SystemMessageEntity systemMessageEntity,
        WorkspaceSpec workspaceSpec
        ) {
    public static ConversationEntity empty(String sessionName, WorkspaceSpec workspaceSpec,
                                           SystemMessageEntity systemMessage, Serializable sessionId,
                                           List<Message> messages) {
        return ConversationEntity.builder()
                .sessionName(sessionName)
                .messages(messages)
                .tokenUsageEntity(TokenUsageEntity.empty())
                .systemMessageEntity(systemMessage)
                .workspaceSpec(workspaceSpec)
                .sessionId(sessionId)
                .build();
    }
    public static ConversationEntity empty(String sessionName, WorkspaceSpec workspaceSpec,
                                           SystemMessageEntity systemMessage, Serializable sessionId) {
        return empty(sessionName, workspaceSpec, systemMessage, sessionId, new LinkedList<>());
    }

    public @NonNull ConversationEntity withSessionId(@NonNull Serializable sessionId) {
        return new ConversationEntity(sessionId, sessionName, messages, tokenUsageEntity,
                systemMessageEntity, workspaceSpec);
    }

    public ConversationEntity withSessionName(String sessionName) {
        return new ConversationEntity(sessionId, sessionName, messages, tokenUsageEntity,
                systemMessageEntity, workspaceSpec);
    }
}
