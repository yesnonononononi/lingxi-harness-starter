package com.summit.adapter.langchain4j.model.provider;

import com.summit.adapter.langchain4j.model.ImageModelAdapter;
import com.summit.core.conf.ModelConfig;
import com.summit.core.model.DefaultModelProviderNames;
import com.summit.core.model.image.ImageModel;
import com.summit.core.model.image.ImageModelProvider;
import dev.langchain4j.http.client.HttpClientBuilderFactory;
import dev.langchain4j.http.client.jdk.JdkHttpClient;
import dev.langchain4j.model.openai.OpenAiImageModel;
import java.util.Objects;

public class CommonImageModelProvider implements ImageModelProvider {
    private final HttpClientBuilderFactory httpClientBuilderFactory;

    public CommonImageModelProvider() {
        this(JdkHttpClient::builder);
    }

    /** Each model needs a fresh mutable builder; client pools may still be shared by the factory. */
    public CommonImageModelProvider(HttpClientBuilderFactory httpClientBuilderFactory) {
        this.httpClientBuilderFactory = Objects.requireNonNull(httpClientBuilderFactory, "httpClientBuilderFactory");
    }

    @Override
    public String name() {
        return DefaultModelProviderNames.DEFAULT_IMAGE;
    }

    @Override
    public ImageModel create(ModelConfig config) {
        OpenAiImageModel.OpenAiImageModelBuilder toBuild = OpenAiImageModel.builder()
                .httpClientBuilder(Objects.requireNonNull(httpClientBuilderFactory.create(), "HTTP client factory returned null"))
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
