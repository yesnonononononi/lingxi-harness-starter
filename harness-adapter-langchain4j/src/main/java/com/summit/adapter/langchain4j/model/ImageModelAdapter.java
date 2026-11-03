package com.summit.adapter.langchain4j.model;

import com.summit.adapter.langchain4j.codec.media.ImageInfoAdapter;
import com.summit.adapter.langchain4j.codec.message.OpenAiMessageCodecAdapter;
import com.summit.core.agent.Image;
import com.summit.core.conversation.api.ChatResponseEntity;
import com.summit.core.conversation.message.AiMessageEntity;
import com.summit.core.model.image.ImageModel;
import com.summit.core.model.image.ImageGenerationOptions;
import dev.langchain4j.model.output.Response;

import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/** Adapts image generation and editing without exposing SDK types to core. */
public class ImageModelAdapter implements ImageModel {
    private final dev.langchain4j.model.image.ImageModel delegate;
    private final ImageGenerationOptions defaults;
    private final Function<ImageGenerationOptions, dev.langchain4j.model.image.ImageModel> delegateFactory;
    private final OpenAiMessageCodecAdapter messageCodec = new OpenAiMessageCodecAdapter();

    public ImageModelAdapter(dev.langchain4j.model.image.ImageModel delegate) {
        this(delegate, ImageGenerationOptions.EMPTY, null);
    }

    /** The factory creates isolated SDK wrappers while sharing the provider's HTTP transport. */
    public ImageModelAdapter(dev.langchain4j.model.image.ImageModel delegate, ImageGenerationOptions defaults,
                             Function<ImageGenerationOptions, dev.langchain4j.model.image.ImageModel> delegateFactory) {
        this.delegate = Objects.requireNonNull(delegate, "image model");
        this.defaults = (defaults == null ? ImageGenerationOptions.EMPTY : defaults).withDefaults(null);
        this.delegateFactory = delegateFactory;
    }

    @Override
    public ChatResponseEntity generate(String prompt) {
        return generate(prompt, (ImageGenerationOptions) null);
    }

    @Override
    public ChatResponseEntity generate(String prompt, int n) {
        return generate(prompt, new ImageGenerationOptions(null, null, n));
    }

    @Override
    public ChatResponseEntity generate(String prompt, ImageGenerationOptions options) {
        ImageGenerationOptions effective = (options == null ? ImageGenerationOptions.EMPTY : options).withDefaults(defaults);
        dev.langchain4j.model.image.ImageModel selected = selectDelegate(effective);
        if (effective.count() == 1) {
            Response<dev.langchain4j.data.image.Image> response = selected.generate(prompt);
            return toResponse(response, List.of(response.content()));
        }
        Response<List<dev.langchain4j.data.image.Image>> response = selected.generate(prompt, effective.count());
        return toResponse(response, response.content());
    }

    @Override
    public ChatResponseEntity edit(Image image, String prompt) {
        return edit(image, prompt, null);
    }

    @Override
    public ChatResponseEntity edit(Image image, String prompt, ImageGenerationOptions options) {
        Response<dev.langchain4j.data.image.Image> response = resolveEditDelegate(options)
                .edit(ImageInfoAdapter.toLangchain4j(image), prompt);
        return toResponse(response, List.of(response.content()));
    }

    @Override
    public ChatResponseEntity edit(Image image, Image mask, String prompt) {
        return edit(image, mask, prompt, null);
    }

    @Override
    public ChatResponseEntity edit(Image image, Image mask, String prompt, ImageGenerationOptions options) {
        Response<dev.langchain4j.data.image.Image> response = resolveEditDelegate(options).edit(
                ImageInfoAdapter.toLangchain4j(image), ImageInfoAdapter.toLangchain4j(mask), prompt);
        return toResponse(response, List.of(response.content()));
    }

    private dev.langchain4j.model.image.ImageModel resolveEditDelegate(ImageGenerationOptions options) {
        // The SDK edit API returns one image; generation batch defaults must not change legacy edits.
        ImageGenerationOptions effective = (options == null ? ImageGenerationOptions.EMPTY : options)
                .withDefaults(new ImageGenerationOptions(defaults.size(), defaults.quality(), 1));
        if (effective.count() != 1) throw new UnsupportedOperationException("Image editing supports exactly one output image");
        return selectDelegate(effective);
    }

    private dev.langchain4j.model.image.ImageModel selectDelegate(ImageGenerationOptions effective) {
        if (!Objects.equals(effective.size(), defaults.size()) || !Objects.equals(effective.quality(), defaults.quality())) {
            if (delegateFactory == null) throw new UnsupportedOperationException("Per-call image specifications are not supported");
            return delegateFactory.apply(effective);
        }
        return delegate;
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
