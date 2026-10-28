package com.summit.harness.springbootautoconfigure.config;

import com.summit.core.agent.AgentRequest;
import com.summit.core.agent.Execution;
import com.summit.core.compact.ContextAttachmentProvider;
import com.summit.core.compact.ContextCompactRequest;
import com.summit.core.compact.Tokenizer;
import com.summit.core.conf.ModelConfig;
import com.summit.core.conversation.ConversationManager;
import com.summit.core.conversation.api.ChatRequestEntity;
import com.summit.core.conversation.api.ChatResponseEntity;
import com.summit.core.conversation.event.RuntimeEventPublisher;
import com.summit.core.conversation.message.AiMessageEntity;
import com.summit.core.conversation.message.Message;
import com.summit.core.conversation.message.SystemMessageEntity;
import com.summit.core.conversation.message.UserMessageEntity;
import com.summit.core.runtime.loop.ExecutionRepository;
import com.summit.core.runtime.loop.ExecutionControlSignal;
import com.summit.harness.springbootautoconfigure.properties.agent.AgentChatProperties;
import com.summit.runtime.agent.AgentConfig;
import com.summit.runtime.compact.DefaultContextPruner;
import com.summit.runtime.compact.DefaultModelCompacter;
import com.summit.runtime.compact.RequestCompactModelResolver;
import com.summit.runtime.conversation.DefaultTokenizer;
import com.summit.runtime.loop.control.InMemoryActiveExecutionRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class ContextAttachmentAutoConfigurationTest {
    @Test
    void independentBeansReachPruningSummaryInputAndRebuiltContextInOrder() {
        AtomicReference<ChatRequestEntity> compactRequest = new AtomicReference<>();
        try (AnnotationConfigApplicationContext context = context(compactRequest)) {
            context.registerBean("laterState", ContextAttachmentProvider.class, () -> provider(30, "CHECKLIST"));
            context.registerBean("earlierState", ContextAttachmentProvider.class, () -> provider(-10, "PLAN"));
            context.refresh();
            assertEquals(2, context.getBeansOfType(ContextAttachmentProvider.class).size());
            assertFalse(context.containsBean("contextAttachmentProvider"));

            Execution execution = execution();
            context.getBean(ConversationManager.class).rebuildContext("summary", execution);
            assertProtected(execution);

            execution = execution();
            assertTrue(context.getBean(DefaultContextPruner.class).compact(new ContextCompactRequest(execution)));
            assertProtected(execution);

            execution = execution();
            assertTrue(context.getBean(DefaultModelCompacter.class).compact(new ContextCompactRequest(execution)));
            String input = compactRequest.get().getMessages().getLast().text();
            assertTrue(input.contains("[PROTECTED APPLICATION STATE]\nPLAN\n\nCHECKLIST"), input);
            assertProtected(execution);
        }
    }

    @Test
    void applicationsWithoutProvidersKeepTheEmptyDefault() {
        try (AnnotationConfigApplicationContext context = context(new AtomicReference<>())) {
            context.refresh();
            assertEquals(1, context.getBeansOfType(ContextAttachmentProvider.class).size());
            assertSame(ContextAttachmentProvider.NONE, context.getBean(ContextAttachmentProvider.class));
            Execution execution = execution();
            context.getBean(ConversationManager.class).rebuildContext("summary", execution);
            assertTrue(execution.getMessages().stream().noneMatch(message -> message.text().contains("CHECKLIST")));
        }
    }

    private AnnotationConfigApplicationContext context(AtomicReference<ChatRequestEntity> compactRequest) {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.registerBean(AgentConfig.class, () -> AgentConfig.builder().maxTokens(10_000).build());
        context.registerBean(Tokenizer.class, () -> new DefaultTokenizer());
        context.registerBean(RuntimeEventPublisher.class, () -> new RuntimeEventPublisher(List.of()));
        context.registerBean(AgentChatProperties.class, AgentChatProperties::new);
        context.registerBean(ExecutionRepository.class, SnapshotRepository::new);
        context.registerBean(RequestCompactModelResolver.class, () -> new RequestCompactModelResolver(
                ModelConfig.builder().build(), null, null, request -> {
                    compactRequest.set(request);
                    return ChatResponseEntity.builder().aiMessageEntity(AiMessageEntity.builder()
                            .text("A compact summary.").build()).build();
                }));
        context.register(ConversationConfig.class);
        return context;
    }

    private Execution execution() {
        return Execution.builder().id("123").agentRequest(AgentRequest.builder().build())
                .messages(new ArrayList<>(List.of(SystemMessageEntity.builder().text("core prompt").build(),
                        UserMessageEntity.from("task"), AiMessageEntity.builder().text("work").build()))).build();
    }

    private void assertProtected(Execution execution) {
        List<Message> protectedMessages = execution.getMessages().stream()
                .filter(SystemMessageEntity.class::isInstance)
                .filter(message -> message.text().contains("PLAN\n\nCHECKLIST")).toList();
        assertEquals(1, protectedMessages.size());
    }

    private ContextAttachmentProvider provider(int order, String text) {
        return new ContextAttachmentProvider() {
            @Override public int order() { return order; }
            @Override public Optional<String> attachment(Serializable executionId) { return Optional.of(text); }
        };
    }

    private static class SnapshotRepository implements ExecutionRepository {
        private final InMemoryActiveExecutionRegistry controls = new InMemoryActiveExecutionRegistry();
        @Override public void save(Execution execution) { }
        @Override public Optional<Execution> findById(String executionId) { return Optional.empty(); }
        @Override public ExecutionControlSignal register(String executionId) { return controls.register(executionId); }
        @Override public void unregister(ExecutionControlSignal signal) { controls.unregister(signal); }
        @Override public void requireSuspend(String executionId) { controls.requireSuspend(executionId); }
        @Override public void requireCancel(String executionId) { controls.requireCancel(executionId); }
    }
}
