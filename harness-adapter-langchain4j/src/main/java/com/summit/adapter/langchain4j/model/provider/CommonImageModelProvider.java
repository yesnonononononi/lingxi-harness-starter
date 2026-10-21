package com.summit.adapter.langchain4j.model.provider;

import com.summit.adapter.langchain4j.model.ImageModelAdapter;
import com.summit.core.conf.ModelConfig;
import com.summit.core.model.DefaultModelProviderNames;
import com.summit.core.model.image.ImageModel;
import com.summit.core.model.image.ImageModelProvider;
import dev.langchain4j.model.openai.OpenAiImageModel;

public class CommonImageModelProvider implements ImageModelProvider {
    @Override
    public String name() {
        return DefaultModelProviderNames.DEFAULT_IMAGE;
    }

    @Override
    public ImageModel create(ModelConfig config) {
        OpenAiImageModel.OpenAiImageModelBuilder toBuild = OpenAiImageModel.builder()
                .apiKey(config.getApiKey())
                .baseUrl(config.getBaseUrl())
                .timeout(config.getTimeout())
                .modelName(config.getModelName())
                .maxRetries(config.getMaxRetries());

        ModelConfig.ImageOptions imageOptions = config.getImageOptions();

        if (imageOptions != null) {
            toBuild.customHeaders(imageOptions.customHeaders());
            if (imageOptions.size() != null && !imageOptions.size().isBlank()) toBuild.size(imageOptions.size());
            if (imageOptions.quality() != null && !imageOptions.quality().isBlank()) toBuild.quality(imageOptions.quality());
        }

        return new ImageModelAdapter(toBuild.build());
    }
}
