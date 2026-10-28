package com.summit.runtime.model;

import com.summit.core.conversation.api.ChatResponseEntity;
import com.summit.core.model.ModelChatCommand;
import com.summit.core.model.chat.ChatModel;
import lombok.AllArgsConstructor;

@AllArgsConstructor
public class DefaultChatModelInvoker extends AbstractInvoker {
    private ChatModel chatModel;

    @Override
    protected ChatResponseEntity action(ModelChatCommand chatCommand) {
        ChatResponseEntity chatResponse = chatModel.chat(chatCommand.chatRequest());
        if (chatCommand.invocationUsage() != null) {
            chatCommand.invocationUsage().recordUsage(chatCommand.responseId(), chatResponse.getTokenUsage());
        }
        chatResponse.setResponseId(chatCommand.responseId());
        return chatResponse;
    }




}
