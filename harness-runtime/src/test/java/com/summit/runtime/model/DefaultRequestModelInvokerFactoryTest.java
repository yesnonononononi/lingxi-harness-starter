package com.summit.runtime.model;

import com.summit.core.conversation.api.ChatResponseEntity;
import com.summit.core.conversation.event.RuntimeEventPublisher;
import com.summit.core.exception.NoSuchModelProviderException;
import com.summit.core.model.ChatModel;
import com.summit.core.model.ModelConfig;
import com.summit.core.model.ModelProvider;
import com.summit.core.model.ModelProviderRegistry;
import com.summit.core.model.RequestModelInvokerFactory;
import com.summit.core.model.streaming.StreamingChatModel;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DefaultRequestModelInvokerFactoryTest {

    @Test
    void usesConfiguredDefaultAndInfersNonStreamingMode() {
        ModelProviderRegistry<ChatModel> chats = new ModelProviderRegistry<>();
        chats.register(provider("custom", request -> ChatResponseEntity.builder().build()));
        DefaultRequestModelInvokerFactory factory = factory("custom", chats, new ModelProviderRegistry<>());

        RequestModelInvokerFactory.Selection selection = factory.select((String) null);

        assertEquals("custom", selection.providerName());
        assertFalse(selection.streaming());
    }

    @Test
    void infersStreamingModeFromProviderType() {
        ModelProviderRegistry<StreamingChatModel> streams = new ModelProviderRegistry<>();
        streams.register(provider("custom-stream", (request, handler) -> { }));
        DefaultRequestModelInvokerFactory factory = factory("default", new ModelProviderRegistry<>(), streams);

        assertTrue(factory.select("custom-stream").streaming());
    }

    @Test
    void rejectsUnknownAndAmbiguousProviderNames() {
        ModelProviderRegistry<ChatModel> chats = new ModelProviderRegistry<>();
        ModelProviderRegistry<StreamingChatModel> streams = new ModelProviderRegistry<>();
        chats.register(provider("same", request -> null));
        streams.register(provider("same", (request, handler) -> { }));
        DefaultRequestModelInvokerFactory factory = factory("default", chats, streams);

        assertThrows(NoSuchModelProviderException.class, () -> factory.select("missing"));
        assertThrows(IllegalArgumentException.class, () -> factory.select("same"));
    }

    @Test
    void distinctRequestConfigsUnderSameProviderCreateDistinctSelections() {
        ModelProviderRegistry<ChatModel> chats = new ModelProviderRegistry<>();
        List<ModelConfig> created = new ArrayList<>();
        chats.register(recordingProvider("default", created));
        DefaultRequestModelInvokerFactory factory = factory("default", chats, new ModelProviderRegistry<>());

        RequestModelInvokerFactory.Selection fast = factory.select(ModelConfig.builder()
                .provider("default")
                .baseUrl("https://fast.example")
                .modelName("gpt-4o-mini")
                .apiKey("key-a")
                .build());
        RequestModelInvokerFactory.Selection strong = factory.select(ModelConfig.builder()
                .provider("default")
                .baseUrl("https://strong.example")
                .modelName("gpt-4o")
                .apiKey("key-b")
                .build());

        assertNotSame(fast, strong);
        assertEquals(2, created.size());
        assertEquals("gpt-4o-mini", created.get(0).getModelName());
        assertEquals("https://strong.example", created.get(1).getBaseUrl());
    }

    @Test
    void reusesSelectionForIdenticalRequestConfig() {
        ModelProviderRegistry<ChatModel> chats = new ModelProviderRegistry<>();
        List<ModelConfig> created = new ArrayList<>();
        chats.register(recordingProvider("default", created));
        DefaultRequestModelInvokerFactory factory = factory("default", chats, new ModelProviderRegistry<>());

        ModelConfig config = ModelConfig.builder()
                .provider("default")
                .baseUrl("https://same.example")
                .modelName("gpt-4o")
                .build();

        assertSame(factory.select(config), factory.select(ModelConfig.builder()
                .provider("default")
                .baseUrl("https://same.example")
                .modelName("gpt-4o")
                .build()));
        assertEquals(1, created.size());
    }

    @Test
    void requestConfigWithoutProviderFallsBackToDefaultProvider() {
        ModelProviderRegistry<ChatModel> chats = new ModelProviderRegistry<>();
        List<ModelConfig> created = new ArrayList<>();
        chats.register(recordingProvider("custom", created));
        DefaultRequestModelInvokerFactory factory = factory("custom", chats, new ModelProviderRegistry<>());

        RequestModelInvokerFactory.Selection selection = factory.select(ModelConfig.builder()
                .baseUrl("https://custom.example")
                .modelName("custom-model")
                .build());

        assertEquals("custom", selection.providerName());
        assertEquals(1, created.size());
        assertEquals("custom", created.get(0).getProvider());
        assertEquals("custom-model", created.get(0).getModelName());
    }

    @Test
    void rejectsNullRequestConfig() {
        DefaultRequestModelInvokerFactory factory = factory("default",
                new ModelProviderRegistry<>(), new ModelProviderRegistry<>());

        assertThrows(NullPointerException.class, () -> factory.select((ModelConfig) null));
    }

    private static DefaultRequestModelInvokerFactory factory(String defaultName,
            ModelProviderRegistry<ChatModel> chats,
            ModelProviderRegistry<StreamingChatModel> streams) {
        return new DefaultRequestModelInvokerFactory(defaultName, ModelConfig.builder().build(),
                chats, streams, new RuntimeEventPublisher(List.of()));
    }

    private static ModelProvider<ChatModel> recordingProvider(String name, List<ModelConfig> created) {
        return new ModelProvider<>() {
            @Override public String name() { return name; }
            @Override public ChatModel create(ModelConfig config) {
                created.add(config);
                return request -> ChatResponseEntity.builder().build();
            }
        };
    }

    private static <T> ModelProvider<T> provider(String name, T model) {
        return new ModelProvider<>() {
            @Override public String name() { return name; }
            @Override public T create(ModelConfig config) { return model; }
        };
    }
}
