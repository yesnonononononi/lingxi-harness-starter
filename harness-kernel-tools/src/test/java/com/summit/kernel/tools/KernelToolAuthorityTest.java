package com.summit.kernel.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.summit.core.agent.AgentRequest;
import com.summit.core.agent.Execution;
import com.summit.core.compact.ContextAttachmentProvider;
import com.summit.core.conversation.api.ChatResponseEntity;
import com.summit.core.conversation.api.ToolCallRequest;
import com.summit.core.conversation.event.RuntimeEventPublisher;
import com.summit.core.conversation.message.AiMessageEntity;
import com.summit.core.conversation.message.Message;
import com.summit.core.conversation.message.SystemMessageEntity;
import com.summit.core.conversation.message.ToolMessageEntity;
import com.summit.core.conversation.message.UserMessageEntity;
import com.summit.core.runtime.loop.ContextUsageReporter;
import com.summit.core.tool.ToolDefinition;
import com.summit.kernel.tools.compact.ContextCompactToolAutoConfiguration;
import com.summit.kernel.tools.mcp.ListMcpToolsAutoConfiguration;
import com.summit.kernel.tools.search.SearchToolAutoConfiguration;
import com.summit.kernel.tools.skill.ReadSkillToolAutoConfiguration;
import com.summit.runtime.agent.AgentConfig;
import com.summit.runtime.compact.DefaultManualCompacter;
import com.summit.runtime.compact.DefaultModelCompacter;
import com.summit.runtime.conversation.DefaultConversationManager;
import com.summit.runtime.conversation.DefaultTokenizer;
import com.summit.runtime.loop.BoundaryChecker;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Kernel definitions stay available; requests grant tools and runtime policy compacts independently. */
class KernelToolAuthorityTest {
    private static final Set<String> KERNEL_NAMES = Set.of(
            "compact_context", "search_tool", "list_mcp_tools", "read_skill");

    @Test
    void definitionsAreRegisteredWithoutFeatureSwitches() {
        try (Fixture fixture = new Fixture(Map.of())) {
            assertEquals(KERNEL_NAMES, fixture.names());
        }
    }

    @Test
    void removedEnablePropertiesCannotHideDefinitions() {
        try (Fixture fixture = new Fixture(Map.of(
                "lingxi.agent.runtime.tool.context-compact.enabled", false,
                "lingxi.agent.runtime.tool.search-tool.enabled", false,
                "lingxi.agent.runtime.tool.list-mcp-tools.enabled", false,
                "lingxi.agent.runtime.tool.read-skill.enabled", false))) {
            assertEquals(KERNEL_NAMES, fixture.names());
        }
    }

    @Test
    void automaticSummaryDoesNotRequireACompactionToolGrant() {
        try (Fixture fixture = new Fixture(Map.of())) {
            Execution execution = execution(List.of(SystemMessageEntity.builder().text("system").build(),
                    UserMessageEntity.from("history ".repeat(200))));
            fixture.checker(100).before(execution);
            assertEquals(1, fixture.summaries.get());
            assertTrue(execution.getMessages().stream().anyMatch(message -> message.text().contains("short summary")));
            assertTrue(execution.getAgentRequest().getToolList().isEmpty());
        }
    }

    @Test
    void automaticTruncationDoesNotRequireAnyToolGrant() {
        try (Fixture fixture = new Fixture(Map.of(
                "lingxi.agent.runtime.tool.context-compact.truncate-threshold", 0.6))) {
            ToolMessageEntity output = ToolMessageEntity.builder().id("call").name("read_file")
                    .toolOutput("x".repeat(2000)).build();
            Execution execution = execution(List.of(SystemMessageEntity.builder().text("system").build(),
                    AiMessageEntity.builder().toolCalls(List.of(new ToolCallRequest("call", "read_file", 0, "{}"))).build(),
                    output));
            fixture.checker(1000).before(execution);
            assertTrue(output.text().length() < 500);
            assertEquals(0, fixture.summaries.get());
        }
    }

    private static Execution execution(List<Message> history) {
        return Execution.builder().id("execution").messages(new ArrayList<>(history))
                .agentRequest(AgentRequest.builder().messages(history).toolList(List.of()).build()).build();
    }

    private static final class Fixture implements AutoCloseable {
        private final AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        private final AtomicInteger summaries = new AtomicInteger();
        private final DefaultTokenizer tokenizer = new DefaultTokenizer();
        private final DefaultConversationManager conversations = new DefaultConversationManager(ContextAttachmentProvider.NONE);
        private final ContextUsageReporter usage = new ContextUsageReporter(tokenizer, 1000,
                new RuntimeEventPublisher(List.of()), 1);
        private final DefaultModelCompacter model = new DefaultModelCompacter(request -> {
            summaries.incrementAndGet();
            return ChatResponseEntity.builder().aiMessageEntity(AiMessageEntity.builder()
                    .text("{\"summary\":\"short summary\"}").build()).build();
        }, conversations, ContextAttachmentProvider.NONE, usage);

        private Fixture(Map<String, Object> properties) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", properties));
            context.registerBean(ObjectMapper.class, () -> new ObjectMapper());
            context.registerBean(DefaultModelCompacter.class, () -> model);
            context.register(ContextCompactToolAutoConfiguration.class, SearchToolAutoConfiguration.class,
                    ListMcpToolsAutoConfiguration.class, ReadSkillToolAutoConfiguration.class);
            context.refresh();
        }

        private Set<String> names() {
            return context.getBeansOfType(ToolDefinition.class).values().stream()
                    .map(ToolDefinition::name).collect(Collectors.toSet());
        }

        private BoundaryChecker checker(int maxTokens) {
            AgentConfig config = AgentConfig.builder().maxTokens(maxTokens)
                    .squeezeThreshold(context.getBean(AgentConfig.ProgressiveSqueezePolicy.class)).build();
            return new BoundaryChecker(config, tokenizer, conversations,
                    new DefaultManualCompacter(ContextAttachmentProvider.NONE, tokenizer, usage), model);
        }

        @Override
        public void close() {
            context.close();
        }
    }
}
