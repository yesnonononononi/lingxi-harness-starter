package com.summit.adapter.langchain4j.model.provider;

import com.summit.core.conf.ModelConfig;
import com.summit.core.model.ModelProvider;
import dev.langchain4j.http.client.HttpClientBuilderFactory;
import dev.langchain4j.http.client.jdk.JdkHttpClient;
import dev.langchain4j.http.client.jdk.JdkHttpClientBuilder;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProviderHttpClientFactoryTest {
    enum Channel {
        OPENAI_CHAT, OPENAI_STREAM, OPENAI_COMPACT,
        ANTHROPIC_CHAT, ANTHROPIC_STREAM, ANTHROPIC_COMPACT, IMAGE;

        ModelProvider<?> provider(HttpClientBuilderFactory factory) {
            return switch (this) {
                case OPENAI_CHAT -> new OpenAiChatModelProvider(factory);
                case OPENAI_STREAM -> new OpenAiStreamingModelProvider(factory);
                case OPENAI_COMPACT -> new OpenAiCompactContextModelProvider(factory);
                case ANTHROPIC_CHAT -> new AnthropicChatModelProvider(factory);
                case ANTHROPIC_STREAM -> new AnthropicStreamingModelProvider(factory);
                case ANTHROPIC_COMPACT -> new AnthropicCompactContextModelProvider(factory);
                case IMAGE -> new CommonImageModelProvider(factory);
            };
        }
    }

    @ParameterizedTest
    @EnumSource(Channel.class)
    void everyChannelUsesTheApplicationFactory(Channel channel) {
        IllegalStateException sentinel = new IllegalStateException("application HTTP factory reached");
        ModelProvider<?> provider = channel.provider(() -> { throw sentinel; });
        assertEquals(sentinel, assertThrows(IllegalStateException.class,
                () -> provider.create(config(Duration.ofSeconds(1)))));
    }

    @ParameterizedTest
    @EnumSource(Channel.class)
    void concurrentModelsDoNotShareMutableTimeouts(Channel channel) throws Exception {
        CyclicBarrier bothConfigured = new CyclicBarrier(2);
        List<RecordingBuilder> builders = new CopyOnWriteArrayList<>();
        ModelProvider<?> provider = channel.provider(() -> {
            RecordingBuilder builder = new RecordingBuilder(bothConfigured);
            builders.add(builder);
            return builder;
        });
        try (ExecutorService workers = Executors.newFixedThreadPool(2)) {
            Future<?> first = workers.submit(() -> provider.create(config(Duration.ofMillis(100))));
            Future<?> second = workers.submit(() -> provider.create(config(Duration.ofMillis(900))));
            assertNotNull(first.get(10, TimeUnit.SECONDS));
            assertNotNull(second.get(10, TimeUnit.SECONDS));
        }
        assertEquals(2, builders.size());
        assertNotSame(builders.getFirst(), builders.getLast());
        assertEquals(List.of(Duration.ofMillis(100), Duration.ofMillis(900)),
                builders.stream().map(builder -> builder.capturedTimeout).sorted().toList());
    }

    private static ModelConfig config(Duration timeout) {
        return ModelConfig.builder().apiKey("test-key").modelName("test-model")
                .maxTokens(1024).maxRetries(0).timeout(timeout).build();
    }

    private static final class RecordingBuilder extends JdkHttpClientBuilder {
        private final CyclicBarrier barrier;
        private Duration capturedTimeout;

        private RecordingBuilder(CyclicBarrier barrier) {
            this.barrier = barrier;
        }

        @Override
        public JdkHttpClientBuilder readTimeout(Duration timeout) {
            super.readTimeout(timeout);
            try {
                barrier.await(5, TimeUnit.SECONDS);
            } catch (Exception error) {
                throw new IllegalStateException("concurrent model creation did not rendezvous", error);
            }
            return this;
        }

        @Override
        public JdkHttpClient build() {
            capturedTimeout = readTimeout();
            return super.build();
        }
    }
}
