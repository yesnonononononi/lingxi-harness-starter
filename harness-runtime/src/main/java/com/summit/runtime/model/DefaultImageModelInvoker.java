package com.summit.runtime.model;

import com.summit.core.conversation.api.ChatResponseEntity;
import com.summit.core.conversation.message.UserMessageEntity;
import com.summit.core.conversation.message.content.ImageContent;
import com.summit.core.model.ModelChatCommand;
import com.summit.core.model.image.ImageModel;
import lombok.AllArgsConstructor;

/** Image generation is a model invocation, with the same response identity and usage settlement. */
@AllArgsConstructor
public class DefaultImageModelInvoker extends AbstractInvoker {
    private final ImageModel imageModel;

    @Override
    protected ChatResponseEntity action(ModelChatCommand command) {
        UserMessageEntity user = command.chatRequest().getMessages().reversed().stream()
                .filter(UserMessageEntity.class::isInstance)
                .map(UserMessageEntity.class::cast).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Image generation requires a user prompt"));
        String prompt = user.text();
        if (prompt == null || prompt.isBlank()) throw new IllegalArgumentException("Image generation requires a non-blank user prompt");
        if (user.getContent().stream().anyMatch(ImageContent.class::isInstance)) {
            throw new IllegalArgumentException("This invocation supports text-to-image generation; image editing is not supported yet");
        }
        ChatResponseEntity response = imageModel.generate(prompt, command.chatRequest().getImageGenerationOptions());
        if (command.invocationUsage() != null) {
            command.invocationUsage().recordUsage(command.responseId(), response.getTokenUsage());
        }
        response.setResponseId(command.responseId());
        return response;
    }
}
