package com.summit.core.model;


import com.summit.core.conversation.api.ChatRequestEntity;
import lombok.Builder;

@Builder
public record ModelChatCommand (
        ChatRequestEntity chatRequest,
        StreamingChatResponseHandler streamingChatResponseHandler,
        boolean streaming,
        boolean thinking
){

}
