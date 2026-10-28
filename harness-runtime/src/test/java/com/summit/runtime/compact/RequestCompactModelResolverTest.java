package com.summit.runtime.compact;

import com.summit.core.agent.AgentRequest;
import com.summit.core.agent.Execution;
import com.summit.core.compact.ContextAttachmentProvider;
import com.summit.core.compact.ContextCompactRequest;
import com.summit.core.conf.ModelConfig;
import com.summit.core.conf.SensitiveDataProvider;
import com.summit.core.conf.SensitiveDataSpec;
import com.summit.core.conversation.api.ChatResponseEntity;
import com.summit.core.conversation.event.RuntimeEventPublisher;
import com.summit.core.conversation.message.AiMessageEntity;
import com.summit.core.conversation.message.UserMessageEntity;
import com.summit.core.json.ExecutionJson;
import com.summit.core.model.ModelProvider;
import com.summit.core.model.ModelProviderRegistry;
import com.summit.core.model.chat.ChatModel;
import com.summit.core.runtime.loop.ContextUsageReporter;
import com.summit.core.tool.ToolExecution;
import com.summit.runtime.conversation.DefaultConversationManager;
import com.summit.runtime.conversation.DefaultTokenizer;
import com.summit.runtime.loop.control.InMemoryActiveExecutionRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RequestCompactModelResolverTest {
    private final List<ModelConfig> selected = new ArrayList<>();
    private final ModelProviderRegistry<ChatModel> providers = new ModelProviderRegistry<>();
    private final ChatModel fallback = request -> { throw new AssertionError("Application compact model must not be called"); };

    private RequestCompactModelResolver resolver(SensitiveDataProvider<ModelConfig> configurations) {
        return new RequestCompactModelResolver(ModelConfig.builder().modelName("application").build(),
                configurations, providers, fallback);
    }

    private void register(String provider) {
        providers.register(new ModelProvider<>() {
            public String name() { return provider; }
            public ChatModel create(ModelConfig config) {
                selected.add(config);
                return request -> ChatResponseEntity.builder().aiMessageEntity(AiMessageEntity.builder()
                        .text("{\"summary\":\"" + config.getModelName() + "\"}").build()).build();
            }
        });
    }

    private ModelConfig current(String name, String provider) {
        return ModelConfig.builder().baseUrl("https://" + name + ".example/v1").apiKey(name + "-key")
                .modelName(name).provider(provider).timeout(Duration.ofSeconds(17)).maxTokens(5000)
                .reasoningEffort("high").returnThinking(true).sendThinking(true).maxRetries(2)
                .anthropicOptions(new ModelConfig.AnthropicOptions("adaptive", null, true, "1h", true))
                .reuseModelForCompaction(true).build();
    }

    private AgentRequest request(long id, int version) {
        return AgentRequest.builder().messages(List.of(UserMessageEntity.from("history")))
                .modelConfigSpec(new SensitiveDataSpec(id, version)).modelProvider("ignored").build();
    }

    @Test
    void reuseUsesTheExactRequestRevisionAndSynchronousProviderWithoutChangingTheSource() {
        register("default");
        ModelConfig config = current("request-model", "default-streaming");
        RequestCompactModelResolver resolver = resolver((id, version) -> {
            assertEquals(7L, id.longValue());
            assertEquals(3, version);
            return config;
        });
        resolver.resolve(request(7, 3));
        assertEquals(config.toBuilder().provider("default").build(), selected.getFirst());
        assertEquals("default-streaming", config.getProvider());
    }

    @Test
    void reuseRetainsAnthropicAndCustomProvidersAndTakesPrecedenceOverIndependentSettings() {
        register("anthropic");
        register("business");
        ModelConfig anthropic = current("claude-model", "anthropic-streaming")
                .toBuilder().compactModel(new ModelConfig.CompactModelConfig(null, null, null)).build();
        resolver((id, version) -> anthropic).resolve(request(1, 1));
        ModelConfig custom = current("business-model", "business");
        resolver((id, version) -> custom).resolve(request(2, 1));
        assertEquals("anthropic", selected.getFirst().getProvider());
        assertEquals(custom, selected.getLast());
    }

    @Test
    void independentConnectionIsExplicitAndDoesNotBorrowMainCredentials() {
        register("default-compact");
        register("anthropic-compact");
        for (String model : List.of("summary", "claude-summary")) {
            ModelConfig config = current("main", "default-streaming").toBuilder().reuseModelForCompaction(false)
                    .compactModel(new ModelConfig.CompactModelConfig("https://compact.example/v1", "compact-key", model)).build();
            resolver((id, version) -> config).resolve(request(1, 1));
            ModelConfig compact = selected.getLast();
            assertEquals("https://compact.example/v1", compact.getBaseUrl());
            assertEquals("compact-key", compact.getApiKey());
            assertEquals(model, compact.getModelName());
            assertEquals(model.startsWith("claude") ? "anthropic-compact" : "default-compact", compact.getProvider());
            assertEquals(config.getTimeout(), compact.getTimeout());
            assertFalse(compact.isSendThinking());
            assertNull(compact.getReasoningEffort());
        }
    }

    @Test
    void partialIndependentConfigurationAndUnavailableRevisionsNeverFallBack() {
        for (ModelConfig.CompactModelConfig partial : List.of(
                new ModelConfig.CompactModelConfig(null, "key", "summary"),
                new ModelConfig.CompactModelConfig("https://compact.example", " ", "summary"),
                new ModelConfig.CompactModelConfig("https://compact.example", "key", null))) {
            ModelConfig config = current("main", null).toBuilder().reuseModelForCompaction(false).compactModel(partial).build();
            assertThrows(IllegalArgumentException.class, () -> resolver((id, version) -> config).resolve(request(1, 1)));
        }
        assertThrows(IllegalStateException.class, () -> resolver((id, version) -> null).resolve(request(1, 2)));
        assertThrows(IllegalStateException.class, () -> resolver(null).resolve(request(1, 2)));
        assertTrue(selected.isEmpty());
    }

    @Test
    void applicationConfigAndLegacyRequestsRetainTheirSelectionRules() {
        register("default");
        RequestCompactModelResolver resolver = new RequestCompactModelResolver(current("main", "default-streaming"),
                null, providers, fallback);
        resolver.resolve(AgentRequest.builder().build());
        assertEquals("main", selected.getFirst().getModelName());
        assertSame(fallback, resolver((id, version) -> ModelConfig.builder().build()).resolve(request(1, 1)));
    }

    @Test
    void automaticAndToolCompactionResolveTheirOwnExecutionAndKeepCredentialsOutOfSnapshots() throws Exception {
        register("default");
        ModelConfig first = current("first", "default-streaming");
        ModelConfig second = current("second", "default-streaming");
        Map<Long, ModelConfig> configs = Map.of(1L, first, 2L, second);
        RequestCompactModelResolver resolver = resolver((id, version) -> configs.get(id.longValue()));
        InMemoryActiveExecutionRegistry executions = new InMemoryActiveExecutionRegistry();
        Execution automatic = Execution.create(request(1, 1), "test");
        automatic.setMessages(new ArrayList<>(List.of(UserMessageEntity.from("history"))));
        Execution tool = Execution.create(request(2, 1), "test");
        executions.save(tool);
        ContextUsageReporter usage = new ContextUsageReporter(new DefaultTokenizer(), 10_000,
                new RuntimeEventPublisher(List.of()), 1);
        DefaultModelCompacter compacter = new DefaultModelCompacter(resolver::resolve, executions,
                new DefaultConversationManager(ContextAttachmentProvider.NONE), ContextAttachmentProvider.NONE, usage);

        assertTrue(compacter.compact(new ContextCompactRequest(automatic, null)));
        assertTrue(compacter.execute(ToolExecution.builder().executionId(tool.getId()).args("history").build()).isSuccess());
        assertEquals(List.of("first", "second"), selected.stream().map(ModelConfig::getModelName).toList());
        String snapshot = ExecutionJson.newObjectMapper().writeValueAsString(automatic);
        assertTrue(ExecutionJson.newObjectMapper().writeValueAsString(first).contains("first-key"));
        assertFalse(snapshot.contains("first-key"));
        assertThrows(IllegalStateException.class, () -> compacter.execute(ToolExecution.builder().executionId("missing").build()));
    }
}
