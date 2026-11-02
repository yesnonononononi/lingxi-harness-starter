package com.summit.adapter.langchain4j.model;

import com.summit.adapter.langchain4j.codec.media.ImageInfoAdapter;
import com.summit.adapter.langchain4j.codec.message.OpenAiMessageCodecAdapter;
import com.summit.core.agent.Image;
import com.summit.core.conversation.api.ChatResponseEntity;
import com.summit.core.conversation.message.AiMessageEntity;
import com.summit.core.model.image.ImageModel;
import dev.langchain4j.model.output.Response;

import java.util.List;
import java.util.Objects;

/** Adapts image generation and editing without exposing SDK types to core. */
public class ImageModelAdapter implements ImageModel {
    private final dev.langchain4j.model.image.ImageModel delegate;
    private final OpenAiMessageCodecAdapter messageCodec = new OpenAiMessageCodecAdapter();

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
        List<Image> output = images.stream().map(ImageInfoAdapter::toFramework).toList();
        return ChatResponseEntity.builder()
                .aiMessageEntity(AiMessageEntity.builder().images(output).toolCalls(List.of()).build())
                .meta(ChatResponseEntity.Meta.builder()
                        .images(output)
                        .finishReason(response.finishReason() == null ? ChatResponseEntity.FinishReason.OTHER
                                : ChatResponseEntity.FinishReason.valueOf(response.finishReason().name()))
                        .build())
                .tokenUsage(messageCodec.toTokenUsageEntity(response.tokenUsage()))
                .build();
    }

}
