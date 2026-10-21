package com.summit.adapter.langchain4j.model;

import com.summit.adapter.langchain4j.codec.ImageInfoAdapter;
import com.summit.core.agent.Image;
import com.summit.core.conversation.api.ChatResponseEntity;
import com.summit.core.conversation.message.TokenUsageEntity;
import com.summit.core.model.image.ImageModel;
import dev.langchain4j.model.openai.OpenAiImageTokenUsage;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.model.output.TokenUsage;

import java.util.List;
import java.util.Objects;

/** Adapts image generation and editing without exposing SDK types to core. */
public class ImageModelAdapter implements ImageModel {
    private final dev.langchain4j.model.image.ImageModel delegate;

    public ImageModelAdapter(dev.langchain4j.model.image.ImageModel delegate) {
        this.delegate = Objects.requireNonNull(delegate, "image model");
    }

    @Override
    public ChatResponseEntity generate(String prompt) {
        Response<dev.langchain4j.data.image.Image> response = delegate.generate(prompt);
        return toResponse(response, List.of(response.content()));
    }

    @Override
    public ChatResponseEntity generate(String prompt, int n) {
        if (n <= 0) throw new IllegalArgumentException("Image count must be positive");
        Response<List<dev.langchain4j.data.image.Image>> response = delegate.generate(prompt, n);
        return toResponse(response, response.content());
    }

    @Override
    public ChatResponseEntity edit(Image image, String prompt) {
        Response<dev.langchain4j.data.image.Image> response = delegate.edit(ImageInfoAdapter.toLangchain4j(image), prompt);
        return toResponse(response, List.of(response.content()));
    }

    @Override
    public ChatResponseEntity edit(Image image, Image mask, String prompt) {
        Response<dev.langchain4j.data.image.Image> response = delegate.edit(
                ImageInfoAdapter.toLangchain4j(image), ImageInfoAdapter.toLangchain4j(mask), prompt);
        return toResponse(response, List.of(response.content()));
    }

    private ChatResponseEntity toResponse(Response<?> response, List<dev.langchain4j.data.image.Image> images) {
        return ChatResponseEntity.builder()
                .meta(ChatResponseEntity.Meta.builder()
                        .images(images.stream().map(ImageInfoAdapter::toFramework).toList())
                        .finishReason(response.finishReason() == null ? ChatResponseEntity.FinishReason.OTHER
                                : ChatResponseEntity.FinishReason.valueOf(response.finishReason().name()))
                        .build())
                .tokenUsage(toTokenUsage(response.tokenUsage()))
                .build();
    }

    private TokenUsageEntity toTokenUsage(TokenUsage usage) {
        if (usage == null) return TokenUsageEntity.empty();
        TokenUsageEntity result = TokenUsageEntity.of(orZero(usage.totalTokenCount()),
                orZero(usage.inputTokenCount()), orZero(usage.outputTokenCount()));
        // Aggregate counts include image tokens, just as chat counts include cached/reasoning tokens.
        if (usage instanceof OpenAiImageTokenUsage imageUsage) {
            if (imageUsage.inputTokensDetails() != null) {
                result.getInputTokens().setImageTokens(orZero(imageUsage.inputTokensDetails().imageTokens()));
            }
            if (imageUsage.outputTokensDetails() != null) {
                result.getOutputTokens().setImageTokens(orZero(imageUsage.outputTokensDetails().imageTokens()));
            }
        }
        return result;
    }

    private static int orZero(Integer value) {
        return value == null ? 0 : value;
    }
}
