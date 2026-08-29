package com.summit.harnesscore.model;


import com.summit.harnesscore.conversation.api.ChatRequestEntity;
import lombok.Builder;

@Builder
public record ModelChatCommand (
        ChatRequestEntity chatRequest,
        StreamingChatResponseHandler streamingChatResponseHandler,
        boolean streaming,
        boolean thinking
){

}
