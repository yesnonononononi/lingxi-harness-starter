package com.summit.runtime.model;

import com.summit.core.conf.ModelConfig;
import com.summit.core.conversation.api.ChatRequestEntity;
import com.summit.core.conversation.api.ChatResponseEntity;
import com.summit.core.conversation.message.AiMessageEntity;
import com.summit.core.conversation.message.TokenUsageEntity;
import com.summit.core.conversation.message.UserMessageEntity;
import com.summit.core.model.ModelChatCommand;
import com.summit.core.model.ModelInvocationUsage;
import com.summit.core.model.ModelProvider;
import com.summit.core.model.ModelProviderRegistry;
import com.summit.core.model.RequestModelInvokerFactory;
import com.summit.core.agent.Image;
import com.summit.core.model.image.ImageModel;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ImageModelInvocationTest {
    @Test
    void explicitImageProviderUsesTheLatestPromptAndSettlesUsageUnderTheResponseIdentity() throws Exception {
        ModelProviderRegistry<ImageModel> images = new ModelProviderRegistry<>();
        images.register(new ModelProvider<>() {
            public String name() { return "default-image-model"; }
            public ImageModel create(ModelConfig config) {
                return prompt -> {
                    assertEquals("draw a boat", prompt);
                    return ChatResponseEntity.builder().aiMessageEntity(AiMessageEntity.builder().toolCalls(List.of()).build())
                            .tokenUsage(TokenUsageEntity.of(95, 15, 80)).build();
                };
            }
        });
        DefaultRequestModelInvokerFactory factory = new DefaultRequestModelInvokerFactory("default", ModelConfig.builder().build(),
                new ModelProviderRegistry<>(), new ModelProviderRegistry<>(), images);
        RequestModelInvokerFactory.Selection selection = factory.select(ModelConfig.builder().provider("default-image-model").modelName("arbitrary-name").build());
        assertFalse(selection.streaming());
        ModelInvocationUsage usage = new ModelInvocationUsage();
        ChatResponseEntity response = selection.invoker().invoke(() -> ModelChatCommand.builder().responseId("100")
                .invocationUsage(usage).chatRequest(ChatRequestEntity.builder().messages(List.of(
                        UserMessageEntity.from("old prompt"), UserMessageEntity.from("draw a boat"))).build()).build());
        assertEquals("100", response.getResponseId());
        assertEquals(95, usage.finish().getTotalTokens());
    }

    @Test
    void unsupportedImageInputFailsBeforeGenerationRatherThanIgnoringTheAttachment() {
        DefaultImageModelInvoker invoker = new DefaultImageModelInvoker(prompt -> { throw new AssertionError("must not call model"); });
        assertThrows(IllegalArgumentException.class, () -> invoker.invoke(() -> ModelChatCommand.builder()
                .chatRequest(ChatRequestEntity.builder().messages(List.of(UserMessageEntity.from("edit", Image.from("aW1hZ2U=")))).build()).build()));
    }
}
