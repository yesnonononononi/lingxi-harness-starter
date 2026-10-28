package com.summit.harness.springbootautoconfigure.config;

import com.summit.core.conf.ModelConfig;
import com.summit.core.model.ModelProvider;
import dev.langchain4j.http.client.HttpClientBuilderFactory;
import dev.langchain4j.http.client.jdk.JdkHttpClientBuilder;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class ModelHttpClientFactoryRegistrationTest {
    @Test
    void defaultFactoryKeepsJdkTransport() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(ModelProviderConfig.class)) {
            assertInstanceOf(JdkHttpClientBuilder.class, context.getBean(HttpClientBuilderFactory.class).create());
        }
    }

    @Test
    void applicationFactoryReachesEveryModelChannel() {
        AtomicInteger creations = new AtomicInteger();
        HttpClientBuilderFactory factory = () -> {
            creations.incrementAndGet();
            return new JdkHttpClientBuilder();
        };
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean("applicationHttpClientFactory", HttpClientBuilderFactory.class, () -> factory);
            context.register(ModelProviderConfig.class);
            context.refresh();
            assertEquals(1, context.getBeansOfType(HttpClientBuilderFactory.class).size());
            assertSame(factory, context.getBean(HttpClientBuilderFactory.class));
            List<String> names = List.of("chatModelModelProvider", "streamingChatModelModelProvider",
                    "compactContextChatModelProvider", "anthropicChatModelProvider", "anthropicStreamingModelProvider",
                    "anthropicCompactContextModelProvider", "commonImageModelProvider");
            for (String name : names) {
                ModelProvider<?> provider = context.getBean(name, ModelProvider.class);
                int previous = creations.get();
                assertNotNull(provider.create(ModelConfig.builder().apiKey("test").modelName("test")
                        .maxTokens(1024).maxRetries(0).build()));
                assertEquals(previous + 1, creations.get(), name);
            }
        }
    }
}
