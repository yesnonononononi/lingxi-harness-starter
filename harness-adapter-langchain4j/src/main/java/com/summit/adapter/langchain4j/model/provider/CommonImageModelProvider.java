package com.summit.adapter.langchain4j.model.provider;

import com.summit.adapter.langchain4j.model.ImageModelAdapter;
import com.summit.core.conf.ModelConfig;
import com.summit.core.model.DefaultModelProviderNames;
import com.summit.core.model.image.ImageModel;
import com.summit.core.model.image.ImageModelProvider;
import com.summit.core.model.image.ImageGenerationOptions;
import dev.langchain4j.http.client.HttpClient;
import dev.langchain4j.http.client.HttpClientBuilder;
import dev.langchain4j.http.client.HttpClientBuilderFactory;
import dev.langchain4j.http.client.jdk.JdkHttpClient;
import dev.langchain4j.model.openai.OpenAiImageModel;
import java.util.Objects;
import java.time.Duration;

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
        ModelConfig snapshot = config.toBuilder().build();
        ModelConfig.ImageOptions imageOptions = snapshot.getImageOptions();
        // Preserve the existing interpretation of blank configuration defaults as unspecified.
        ImageGenerationOptions defaults = imageOptions == null ? ImageGenerationOptions.EMPTY
                : new ImageGenerationOptions(nonBlank(imageOptions.size()), nonBlank(imageOptions.quality()), imageOptions.count());
        Duration connectTimeout = snapshot.getTimeout() == null ? Duration.ofSeconds(15) : snapshot.getTimeout();
        Duration readTimeout = snapshot.getTimeout() == null ? Duration.ofSeconds(60) : snapshot.getTimeout();
        HttpClient transport = Objects.requireNonNull(httpClientBuilderFactory.create(), "HTTP client factory returned null")
                .connectTimeout(connectTimeout).readTimeout(readTimeout).build();
        return new ImageModelAdapter(build(snapshot, defaults, transport, connectTimeout, readTimeout), defaults,
                effective -> build(snapshot, effective, transport, connectTimeout, readTimeout));
    }

    private OpenAiImageModel build(ModelConfig config, ImageGenerationOptions options, HttpClient transport,
                                   Duration connectTimeout, Duration readTimeout) {
        OpenAiImageModel.OpenAiImageModelBuilder toBuild = OpenAiImageModel.builder()
                .httpClientBuilder(new SharedTransportBuilder(transport, connectTimeout, readTimeout))
                .apiKey(config.getApiKey())
                .baseUrl(config.getBaseUrl())
                .timeout(config.getTimeout())
                .modelName(config.getModelName())
                .maxRetries(config.getMaxRetries());

        ModelConfig.ImageOptions imageOptions = config.getImageOptions();

        if (imageOptions != null) {
            toBuild.customHeaders(imageOptions.customHeaders());
        }
        if (options.size() != null) toBuild.size(options.size());
        if (options.quality() != null) toBuild.quality(options.quality());
        if (options.background() != null) toBuild.background(options.background());
        if (options.outputFormat() != null) toBuild.outputFormat(options.outputFormat());

        return toBuild.build();
    }

    private static String nonBlank(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /** Fresh SDK builders share one immutable transport with the same connection settings. */
    private record SharedTransportBuilder(HttpClient client, Duration connectTimeout, Duration readTimeout)
            implements HttpClientBuilder {
        @Override
        public HttpClientBuilder connectTimeout(Duration timeout) {
            if (!Objects.equals(connectTimeout, timeout)) throw new IllegalArgumentException("Shared transport connect timeout differs");
            return this;
        }

        @Override
        public HttpClientBuilder readTimeout(Duration timeout) {
            if (!Objects.equals(readTimeout, timeout)) throw new IllegalArgumentException("Shared transport read timeout differs");
            return this;
        }

        @Override
        public HttpClient build() {
            return client;
        }
    }
}
