package com.summit.harness.springbootautoconfigure.config;

import com.summit.core.compact.TokenEstimator;
import com.summit.core.agent.AgentRequest;
import com.summit.core.agent.ContextSpec;
import com.summit.core.agent.Execution;
import com.summit.core.compact.CompactSummaryApplier;
import com.summit.core.conf.ModelConfig;
import com.summit.core.conversation.event.RuntimeEventPublisher;
import com.summit.core.conversation.message.Message;
import com.summit.core.conversation.message.UserMessageEntity;
import com.summit.core.model.ModelProviderRegistry;
import com.summit.core.model.chat.ChatModel;
import com.summit.core.runtime.loop.ContextUsageReporter;
import com.summit.core.runtime.loop.ExecutionControlSignal;
import com.summit.core.runtime.loop.ExecutionRepository;
import com.summit.core.tool.ToolDefinition;
import com.summit.core.tool.ToolExecutor;
import com.summit.core.tool.ToolRegistry;
import com.summit.harness.springbootautoconfigure.properties.agent.AgentChatProperties;
import com.summit.runtime.agent.AgentConfig;
import com.summit.runtime.compact.DefaultCompactSummaryApplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the construction order of the context-usage reporter and the tool registry.
 *
 * <p>The registry is assembled from the tool definitions, and one of them is the context-compaction
 * tool whose compacter needs the reporter. A reporter that resolves the registry while it is being
 * created therefore closes a cycle; the application context then fails to start and every
 * {@code @SpringBootTest} in a host application turns into {@code Failed to load ApplicationContext}.
 * The first test loads the real wiring with exactly that shape, so the cycle cannot come back
 * unnoticed; the second proves the reporter still works when an application publishes no tools.</p>
 */
class ConversationConfigContextTest {

    /**
     * Loads the production wiring with a registry that pulls the reporter while it is being built,
     * which is how the host application wires the compaction tool.
     */
    @Test
    void contextLoadsWhenTheToolRegistryNeedsTheReporter() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            // Registered first so the registry is created before the reporter, the order in which
            // an eager lookup inside the reporter factory used to fail.
            context.register(CyclicToolRegistryConfiguration.class);
            context.register(Collaborators.class, ConversationConfig.class);

            context.refresh();

            ContextUsageReporter reporter = context.getBean(ContextUsageReporter.class);
            assertNotNull(context.getBean(ToolRegistry.class), "the registry must be created");

            Execution execution = execution();
            assertTrue(reporter.countInput(execution, execution.getMessages()) > 0);
            assertNotNull(reporter.report(execution));
        }
    }

    /** An application without process-wide tools stays usable: a missing registry is "no tools". */
    @Test
    void reporterWorksWithoutAnyToolRegistry() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.register(Collaborators.class, ConversationConfig.class);

            context.refresh();

            ContextUsageReporter reporter = context.getBean(ContextUsageReporter.class);
            assertInstanceOf(DefaultCompactSummaryApplier.class, context.getBean(CompactSummaryApplier.class));
            Execution execution = execution();
            assertTrue(reporter.countInput(execution, execution.getMessages()) > 0);
            assertNotNull(reporter.report(execution));
            assertEquals(0, context.getBeansOfType(ToolRegistry.class).size());
        }
    }

    @Test
    void applicationSummaryApplierReplacesTheDefaultBean() {
        CompactSummaryApplier custom = (raw, execution) -> false;
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean("businessSummaryApplier", CompactSummaryApplier.class, () -> custom);
            context.register(Collaborators.class, ConversationConfig.class);
            context.refresh();

            assertEquals(1, context.getBeansOfType(CompactSummaryApplier.class).size());
            assertSame(custom, context.getBean(CompactSummaryApplier.class));
        }
    }

    private static Execution execution() {
        AgentRequest request = AgentRequest.builder()
                .contextSpec(ContextSpec.builder()
                        .context(List.of(UserMessageEntity.from("hello")))
                        .build())
                .toolList(List.of("demo"))
                .build();
        return Execution.builder().id("execution-1").agentRequest(request)
                .messages(new ArrayList<>(request.getContextSpec().getContext())).build();
    }

    /** Every collaborator the conversation wiring asks for, stubbed to the cheapest working form. */
    @Configuration(proxyBeanMethods = false)
    static class Collaborators {

        @Bean
        TokenEstimator tokenEstimator() {
            return new CountingTokenEstimator();
        }

        @Bean
        AgentConfig agentConfig() {
            return AgentConfig.builder().maxTokens(1_000).build();
        }

        @Bean
        RuntimeEventPublisher runtimeEventPublisher() {
            return new RuntimeEventPublisher(List.of());
        }

        @Bean
        AgentChatProperties agentChatProperties() {
            return new AgentChatProperties();
        }

        @Bean
        ExecutionRepository executionRepository() {
            return new StubExecutionRepository();
        }

        @Bean(name = "chatModelConfig")
        ModelConfig chatModelConfig() {
            return modelConfig();
        }

        @Bean(name = "compactContextModelConfig")
        ModelConfig compactContextModelConfig() {
            return modelConfig();
        }

        @Bean
        ModelProviderRegistry<ChatModel> modelProviderRegistry() {
            return new ModelProviderRegistry<>();
        }

        @Bean(name = "defaultContextCompactModel")
        ChatModel defaultContextCompactModel() {
            return request -> {
                throw new UnsupportedOperationException("the guard test never runs a model");
            };
        }

        private static ModelConfig modelConfig() {
            return ModelConfig.builder().provider("default").modelName("test-model").maxTokens(1_000).build();
        }
    }

    /** Mirrors the host application: the registry is built from tools that need the reporter. */
    @Configuration(proxyBeanMethods = false)
    static class CyclicToolRegistryConfiguration {

        @Bean
        ToolRegistry toolRegistry(ObjectProvider<ContextUsageReporter> reporter) {
            ContextUsageReporter usage = reporter.getObject();
            assertNotNull(usage);
            return new ToolRegistry(new ArrayList<ToolDefinition<? extends ToolExecutor>>());
        }
    }

    /** The registry is irrelevant to the guard; only its creation order is. */
    static final class StubExecutionRepository implements ExecutionRepository {

        @Override
        public void save(Execution execution) {
            // Snapshots are irrelevant to the guard.
        }

        @Override
        public Optional<Execution> findById(String executionId) {
            return Optional.empty();
        }

        @Override
        public ExecutionControlSignal register(String executionId) {
            return null;
        }

        @Override
        public void unregister(ExecutionControlSignal signal) {
            // Nothing is registered.
        }

        @Override
        public void requireSuspend(String executionId) {
            // Nothing is running.
        }

        @Override
        public void requireCancel(String executionId) {
            // Nothing is running.
        }
    }

    /** Deterministic estimator: the guard is about wiring, not about tokenization. */
    static final class CountingTokenEstimator implements TokenEstimator {

        @Override
        public int estimateText(String text) {
            return text == null ? 0 : text.length();
        }

        @Override
        public int estimateMessages(List<Message> messages) {
            return messages == null ? 0 : messages.size() * 10;
        }
    }
}
