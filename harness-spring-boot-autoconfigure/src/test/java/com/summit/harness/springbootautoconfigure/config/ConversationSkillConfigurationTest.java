package com.summit.harness.springbootautoconfigure.config;

import com.summit.core.adapter.TokenEstimator;
import com.summit.core.conf.SkillConfig;
import com.summit.core.conversation.ConversationManager;
import com.summit.core.conversation.event.RuntimeEventPublisher;
import com.summit.core.conversation.message.Message;
import com.summit.core.model.chat.ChatModel;
import com.summit.core.skill.SkillLoader;
import com.summit.core.skill.SkillResolver;
import com.summit.core.skill.SkillResume;
import com.summit.harness.springbootautoconfigure.properties.agent.AgentChatProperties;
import com.summit.runtime.agent.AgentConfig;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ConversationSkillConfigurationTest {
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
        context.register(ConversationConfig.class);
        return context;
    }
}
