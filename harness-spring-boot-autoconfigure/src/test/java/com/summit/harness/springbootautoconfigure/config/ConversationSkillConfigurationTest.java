package com.summit.harness.springbootautoconfigure.config;

import com.summit.core.adapter.TokenEstimator;
import com.summit.core.conf.SkillConfig;
import com.summit.core.conf.ModelConfig;
import com.summit.core.conf.McpConfig;
import com.summit.core.conf.SensitiveDataProvider;
import com.summit.core.conf.SensitiveDataSpec;
import com.summit.core.agent.AgentRequest;
import com.summit.core.conversation.ConversationManager;
import com.summit.core.conversation.event.RuntimeEventPublisher;
import com.summit.core.conversation.message.Message;
import com.summit.core.model.chat.ChatModel;
import com.summit.core.model.ModelProviderRegistry;
import com.summit.core.model.ModelProvider;
import com.summit.core.runtime.loop.ExecutionRepository;
import com.summit.core.skill.SkillLoader;
import com.summit.core.skill.SkillResolver;
import com.summit.core.skill.SkillResume;
import com.summit.harness.springbootautoconfigure.properties.agent.AgentChatProperties;
import com.summit.runtime.agent.AgentConfig;
import com.summit.runtime.loop.control.InMemoryActiveExecutionRegistry;
import com.summit.runtime.compact.RequestCompactModelResolver;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.util.List;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.*;

class ConversationSkillConfigurationTest {
    @Test
    void compactionResolvesTheTypedRequestRevisionProvider() {
        try (AnnotationConfigApplicationContext context = context(false)) {
            context.register(RequestRevisionConfiguration.class);
            context.refresh();
            List<ModelConfig> selected = new ArrayList<>();
            ModelProviderRegistry<ChatModel> providers = context.getBean(ModelProviderRegistry.class);
            providers.register(new ModelProvider<>() {
                public String name() { return "default"; }
                public ChatModel create(ModelConfig config) {
                    selected.add(config);
                    return request -> null;
                }
            });
            context.getBean(RequestCompactModelResolver.class).resolve(AgentRequest.builder()
                    .modelConfigSpec(new SensitiveDataSpec(31L, 2)).build());
            assertEquals("https://request.example/v1", selected.getFirst().getBaseUrl());
            assertEquals("request-key", selected.getFirst().getApiKey());
            assertEquals("default", selected.getFirst().getProvider());
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class RequestRevisionConfiguration {
        @Bean
        SensitiveDataProvider<ModelConfig> modelRevisions() {
            return (id, version) -> {
                assertEquals(31L, id.longValue());
                assertEquals(2, version);
                return ModelConfig.builder().baseUrl("https://request.example/v1").apiKey("request-key")
                        .modelName("request-model").provider("default-streaming").reuseModelForCompaction(true).build();
            };
        }

        @Bean
        SensitiveDataProvider<McpConfig> mcpRevisions() {
            return (id, version) -> { throw new AssertionError("MCP revisions cannot resolve model configuration"); };
        }
    }

    @Test
    void customResolverReplacesDefault() {
        try (AnnotationConfigApplicationContext context = context(false)) {
            SkillResolver custom = path -> new SkillResume("custom", "custom format", path.toString());
            context.registerBean("customResolver", SkillResolver.class, () -> custom);
            context.refresh();
            assertSame(custom, context.getBean(SkillResolver.class));
            assertEquals(1, context.getBeansOfType(SkillResolver.class).size());
        }
    }

    @Test
    void missingCommonMarkDoesNotBreakStartupWithoutSkillRequests() {
        try (AnnotationConfigApplicationContext context = context(true)) {
            context.refresh();
            assertTrue(context.getBeansOfType(SkillResolver.class).isEmpty());
            assertNotNull(context.getBean(ConversationManager.class));
            assertThrows(IllegalStateException.class,
                    () -> context.getBean(SkillLoader.class).load(new SkillConfig(Path.of("skills"))));
        }
    }

    @Test
    void customLoaderDoesNotRequireCommonMark() {
        try (AnnotationConfigApplicationContext context = context(true)) {
            SkillLoader custom = new SkillLoader() {
                public SkillResolver provideSkillResolver() { return null; }
                public List<SkillResume> load(SkillConfig config) {
                    return List.of(new SkillResume("business", "custom source", "entry"));
                }
            };
            context.registerBean("businessLoader", SkillLoader.class, () -> custom);
            context.refresh();
            assertSame(custom, context.getBean(SkillLoader.class));
            assertEquals(1, context.getBeansOfType(SkillLoader.class).size());
        }
    }

    private AnnotationConfigApplicationContext context(boolean hideCommonMark) {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        if (hideCommonMark) context.setClassLoader(new ClassLoader(getClass().getClassLoader()) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.startsWith("org.commonmark.")) throw new ClassNotFoundException(name);
                return super.loadClass(name, resolve);
            }
        });
        context.registerBean(TokenEstimator.class, () -> new TokenEstimator() {
            public int estimateText(String text) { return 0; }
            public int estimateMessages(List<Message> messages) { return 0; }
        });
        context.registerBean(AgentConfig.class, () -> AgentConfig.builder().build());
        context.registerBean(RuntimeEventPublisher.class, () -> new RuntimeEventPublisher(List.of()));
        context.registerBean(AgentChatProperties.class, AgentChatProperties::new);
        context.registerBean("defaultContextCompactModel", ChatModel.class, () -> request -> null);
        context.registerBean("chatModelConfig", ModelConfig.class, () -> ModelConfig.builder().build());
        // 压缩语义是独立的一档配置：装配 ConversationConfig 就得同时给它，不能拿对话配置顶。
        context.registerBean("compactContextModelConfig", ModelConfig.class,
                () -> ModelConfig.builder().maxTokens(32768).reasoningEffort("none").build());
        context.registerBean(ModelProviderRegistry.class, ModelProviderRegistry::new);
        context.registerBean(ExecutionRepository.class, () -> new InMemoryActiveExecutionRegistry());
        context.register(ConversationConfig.class);
        return context;
    }
}
