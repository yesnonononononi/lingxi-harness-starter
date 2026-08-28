package com.summit.harnesscore.conversation;

import com.summit.harnesscore.runtime.Workspace;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.model.output.TokenUsage;
import lombok.Builder;

import java.io.Serializable;
import java.util.LinkedList;
import java.util.List;
@Builder
public record ConversationEntity(
        Serializable sessionId, List<ChatMessage> messages, TokenUsage tokenUsage, SystemMessage systemMessage, Workspace workspace
        ) {
    public static ConversationEntity empty(Workspace workspace,SystemMessage systemMessage,Serializable sessionId,List<ChatMessage> messages){
        return ConversationEntity.builder()
                .messages(messages)
                .tokenUsage(new TokenUsage(0,0,0))
                .systemMessage(systemMessage)
                .workspace(workspace)
                .sessionId(sessionId)
                .build();
    }
    public static ConversationEntity empty(Workspace workspace,SystemMessage systemMessage,Serializable sessionId) {
        return empty(workspace, systemMessage, sessionId, new LinkedList<>());
    }
}
