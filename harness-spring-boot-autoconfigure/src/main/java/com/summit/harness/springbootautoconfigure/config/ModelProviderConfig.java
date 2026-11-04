package com.summit.harness.springbootautoconfigure.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import anthropic.AnthropicChatModel;
import com.summit.core.model.DefaultModelProviderNames;
import com.summit.core.model.ModelProvider;
import com.summit.core.conf.ModelConfig;
import com.summit.core.model.ModelProviderRegistry;
import com.summit.core.model.chat.ChatModel;
import com.summit.core.model.image.ImageModel;
import com.summit.core.model.streaming.StreamingChatModel;
import com.summit.harness.springbootautoconfigure.properties.CompactContextModelProperties;
import com.summit.harness.springbootautoconfigure.properties.agent.AgentChatProperties;
import com.summit.harness.springbootautoconfigure.properties.agent.AgentImageProperties;
import openAi.DefaultChatModel;
import openAi.MessageSerializer;
import openAi.ModelChatResolver;
import openAi.RequestBodyFactory;
import openAi.ResponseSerializer;
import openAi.image.DefaultImageModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.function.Function;

/**
 * Registers OpenAI and Anthropic chat, streaming and compaction providers. Custom providers registered as beans are collected by the
 * registry automatically.
 *
 * <p>The providers are gated by bean <em>name</em>, not by type. They are contributed into collected
 * lists ({@code List<ModelProvider<ChatModel>>}), and the two chat providers share that list: a
 * type-level condition would let an application provider for one channel suppress the built-in of
 * the other. Named gating keeps each default independently replaceable.</p>
 */
@AutoConfiguration
@EnableConfigurationProperties({AgentChatProperties.class, AgentImageProperties.class, CompactContextModelProperties.class})
public class ModelProviderConfig {

    @Bean
    @ConditionalOnMissingBean(name = "chatModelModelProvider")
    public ModelProvider<ChatModel> chatModelModelProvider(@Qualifier("netModelHttpClient") HttpClient client,
                                                          ModelChatResolver resolver) {
        return provider(DefaultModelProviderNames.DEFAULT, config -> new DefaultChatModel(config, resolver, client));
    }

    @Bean
    @ConditionalOnMissingBean(name = "streamingChatModelModelProvider")
    public ModelProvider<StreamingChatModel> streamingChatModelModelProvider(@Qualifier("netModelHttpClient") HttpClient client,
                                                                           ModelChatResolver resolver) {
        return provider(DefaultModelProviderNames.DEFAULT_STREAMING, config -> new DefaultChatModel(config, resolver, client));
    }

    @Bean(name = "netModelHttpClient", destroyMethod = "close")
    @ConditionalOnMissingBean(name = "netModelHttpClient")
    public HttpClient netModelHttpClient() {
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    }

    @Bean
    @ConditionalOnMissingBean
    public ModelChatResolver netModelChatResolver() {
        ObjectMapper mapper = new ObjectMapper();
        return new ModelChatResolver(new RequestBodyFactory(new MessageSerializer(mapper), mapper),
                mapper, new ResponseSerializer());
    }

    @Bean
    @ConditionalOnMissingBean(name = "netChatModelProvider")
    public ModelProvider<ChatModel> netChatModelProvider(@Qualifier("netModelHttpClient") HttpClient client,
                                                       ModelChatResolver resolver) {
        return new ModelProvider<>() {
            @Override
            public String name() {
                return DefaultModelProviderNames.NET_CHAT;
            }

            @Override
            public ChatModel create(ModelConfig config) {
                return new DefaultChatModel(config, resolver, client);
            }
        };
    }

    @Bean
    @ConditionalOnMissingBean(name = "netStreamingModelProvider")
    public ModelProvider<StreamingChatModel> netStreamingModelProvider(@Qualifier("netModelHttpClient") HttpClient client,
                                                                        ModelChatResolver resolver) {
        return new ModelProvider<>() {
            @Override
            public String name() {
                return DefaultModelProviderNames.NET_STREAMING;
            }

            @Override
            public StreamingChatModel create(ModelConfig config) {
                return new DefaultChatModel(config, resolver, client);
            }
        };
    }

    @Bean(name = "compactContextChatModelProvider")
    @ConditionalOnMissingBean(name = "compactContextChatModelProvider")
    public ModelProvider<ChatModel> compactContextChatModelProvider(@Qualifier("netModelHttpClient") HttpClient client,
                                                                  ModelChatResolver resolver) {
        return new ModelProvider<>() {
            @Override
            public String name() {
                return DefaultModelProviderNames.DEFAULT_COMPACT;
            }

            @Override
            public ChatModel create(ModelConfig config) {
                return new DefaultChatModel(config, resolver, client);
            }
        };
    }

    @Bean
    @ConditionalOnMissingBean(name = "streamingModelProviderRegistry")
    public ModelProviderRegistry<StreamingChatModel> streamingModelProviderRegistry(List<ModelProvider<StreamingChatModel>> providers) {
        ModelProviderRegistry<StreamingChatModel> registry = new ModelProviderRegistry<>();
        providers.forEach(registry::register);
        return registry;
    }

    @Bean
    @ConditionalOnMissingBean(name = "anthropicChatModelProvider")
    public ModelProvider<ChatModel> anthropicChatModelProvider(@Qualifier("netModelHttpClient") HttpClient client) {
        ObjectMapper mapper = new ObjectMapper();
        return provider(DefaultModelProviderNames.ANTHROPIC, config -> new AnthropicChatModel(config, client, mapper));
    }
    @Bean
    @ConditionalOnMissingBean(name = "anthropicStreamingModelProvider")
    public ModelProvider<StreamingChatModel> anthropicStreamingModelProvider(@Qualifier("netModelHttpClient") HttpClient client) {
        ObjectMapper mapper = new ObjectMapper();
        return provider(DefaultModelProviderNames.ANTHROPIC_STREAMING, config -> new AnthropicChatModel(config, client, mapper));
    }

    @Bean
    @ConditionalOnMissingBean(name = "anthropicCompactContextModelProvider")
    public ModelProvider<ChatModel> anthropicCompactContextModelProvider(@Qualifier("netModelHttpClient") HttpClient client) {
        ObjectMapper mapper = new ObjectMapper();
        return provider(DefaultModelProviderNames.ANTHROPIC_COMPACT, config -> new AnthropicChatModel(config, client, mapper));
    }

    @Bean
    @ConditionalOnMissingBean(name = "imageModelConfig")
    public ModelConfig imageModelConfig(AgentImageProperties properties) {
        return ModelConfig.builder()
                .provider(properties.getProvider())
                .baseUrl(properties.getBaseUrl())
                .apiKey(properties.getApiKey())
                .modelName(properties.getModelName())
                .timeout(properties.getTimeout())
                .maxRetries(properties.getMaxRetries())
                .imageOptions(new ModelConfig.ImageOptions(properties.getQuality(), properties.getSize(),
                        properties.getCustomHeaders(), properties.getCount()))
                .build();
    }

    @Bean(name = "commonImageModelProvider")
    @ConditionalOnMissingBean(name = "commonImageModelProvider")
    public ModelProvider<ImageModel> commonImageModelProvider(@Qualifier("netModelHttpClient") HttpClient client) {
        ObjectMapper mapper = new ObjectMapper();
        return new ModelProvider<>() {
            @Override
            public String name() {
                return DefaultModelProviderNames.DEFAULT_IMAGE;
            }

            @Override
            public ImageModel create(ModelConfig config) {
                return new DefaultImageModel(client, mapper, config.toBuilder().build());
            }
        };
    }

    private static <T> ModelProvider<T> provider(String name, Function<ModelConfig, T> factory) {
        return new ModelProvider<>() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public T create(ModelConfig config) {
                return factory.apply(config);
            }
        };
    }

    @Bean
    @ConditionalOnMissingBean(name = "imageModelProviderRegistry")
    public ModelProviderRegistry<ImageModel> imageModelProviderRegistry(List<ModelProvider<ImageModel>> providers) {
        ModelProviderRegistry<ImageModel> registry = new ModelProviderRegistry<>();
        providers.forEach(registry::register);
        return registry;
    }
    @Bean
    @ConditionalOnMissingBean(name = "chatModelProviderRegistry")
    public ModelProviderRegistry<ChatModel> chatModelProviderRegistry(List<ModelProvider<ChatModel>> providers) {
        ModelProviderRegistry<ChatModel> registry = new ModelProviderRegistry<>();
        providers.forEach(registry::register);
        return registry;
    }
}
