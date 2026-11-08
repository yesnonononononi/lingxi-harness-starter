package com.summit.runtime.audit;

import com.summit.core.agent.AgentRequest;
import com.summit.core.agent.ContextSpec;
import com.summit.core.agent.Execution;
import com.summit.core.agent.Image;
import com.summit.core.audit.AuditStatus;
import com.summit.core.audit.DocumentElement;
import com.summit.core.audit.token.TokenAuditDocument;
import com.summit.core.audit.token.tool.ToolAuditDocument;
import com.summit.core.audit.token.tool.ToolElement;
import com.summit.core.compact.ContextUsageMetric;
import com.summit.core.compact.ContextAttachmentProvider;
import com.summit.core.conversation.api.ToolCallRequest;
import com.summit.core.conversation.message.AiMessageEntity;
import com.summit.core.conversation.message.Message;
import com.summit.core.conversation.message.ToolMessageEntity;
import com.summit.core.conversation.message.UserMessageEntity;
import com.summit.core.conversation.message.content.ImageContent;
import com.summit.core.conversation.message.content.PdfFileContent;
import com.summit.core.mcp.McpSession;
import com.summit.core.mcp.McpToolScope;
import com.summit.core.tool.ConcurrentPolicy;
import com.summit.core.tool.ToolDefinition;
import com.summit.core.tool.ToolExecuteResult;
import com.summit.core.tool.ToolExecutor;
import com.summit.core.tool.ToolRegistry;
import com.summit.runtime.agent.AgentConfig;
import com.summit.runtime.conversation.DefaultTokenizer;
import com.summit.runtime.conversation.DefaultConversationManager;
import openAi.OpenAiTokenizer;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DefaultTokenAuditorTest {
    @Test
    void countsJoinedMemoryBodyAndKeepsItWithinSystemTokens() {
        var tokenizer = new DefaultTokenizer(new OpenAiTokenizer());
        String body = "project conventions\nuser preferences";
        var request = AgentRequest.builder().contextSpec(ContextSpec.builder()
                .context(List.of(UserMessageEntity.from("task")))
                .memory(java.util.Arrays.asList("project conventions", null, "  ", "user preferences"))
                .build()).build();
        var execution = Execution.create(request, "agent");
        var conversations = new DefaultConversationManager(ContextAttachmentProvider.NONE, tokenizer);
        conversations.startConversation(execution, null, McpToolScope.EMPTY);
        conversations.startConversation(execution, null, McpToolScope.EMPTY);

        var audit = auditor(tokenizer).analysis(execution);

        assertCount(audit.otherTokens().memoryTokens(), tokenizer.countText(body));
        assertEquals("## Memory Prompt\n" + body, execution.getMessages().getFirst().text());
        assertEquals(tokenizer.count(List.of(execution.getMessages().getFirst())),
                audit.systemTokens().getTokenMeta());
        assertCount(audit.otherTokens().attachmentTokens(), 0);
    }

    @Test
    void countsOnlyRetainedUserAttachmentsWithoutTextOrMessageFraming() {
        var tokenizer = new DefaultTokenizer(new OpenAiTokenizer());
        Image low = Image.builder().base64Data("unused").detailLevel(Image.DetailLevel.LOW).build();
        Image high = Image.builder().base64Data("unused").detailLevel(Image.DetailLevel.HIGH).build();
        var user = UserMessageEntity.from("text must not be counted as an attachment", List.of(low, high));
        var execution = execution(List.of(user, AiMessageEntity.builder().images(List.of(high)).build(),
                UserMessageEntity.builder().build()));

        var audit = auditor(tokenizer).analysis(execution);

        assertCount(audit.otherTokens().attachmentTokens(), 85 + 765);
        assertEquals(tokenizer.count(List.of(user, UserMessageEntity.builder().build())),
                audit.userTokens().getTokenMeta());
        // Audit the current snapshot after image-window trimming, not the original request history.
        execution.setMessages(List.of(UserMessageEntity.from("text only")));
        assertCount(auditor(tokenizer).analysis(execution).otherTokens().attachmentTokens(), 0);
    }

    @Test
    void distinguishesAbsentMemoryFromMissingMessageHistory() {
        var execution = execution(List.of());
        execution.getAgentRequest().getContextSpec().setMemory(null);
        var audit = auditor(new DefaultTokenizer()).analysis(execution);
        assertCount(audit.otherTokens().memoryTokens(), 0);
        assertCount(audit.otherTokens().attachmentTokens(), 0);

        execution.setMessages(null);
        audit = auditor(new DefaultTokenizer()).analysis(execution);
        assertCount(audit.otherTokens().memoryTokens(), 0);
        assertEquals(AuditStatus.NO_DATA, audit.otherTokens().attachmentTokens().getStatus());
        assertNull(audit.otherTokens().attachmentTokens().getTokenMeta());
    }

    @Test
    void isolatesMemoryEstimationFailureFromOtherAuditFields() {
        var tokenizer = new DefaultTokenizer() {
            @Override
            public int countText(String text) {
                if ("broken memory".equals(text)) throw new IllegalStateException("memory failed");
                return super.countText(text);
            }
        };
        var execution = execution(List.of(UserMessageEntity.from("task")));
        execution.getAgentRequest().getContextSpec().setMemory(List.of("broken memory"));

        var audit = assertDoesNotThrow(() -> auditor(tokenizer).analysis(execution));

        assertEquals(AuditStatus.FAILED, audit.otherTokens().memoryTokens().getStatus());
        assertEquals("memory failed", audit.otherTokens().memoryTokens().getAuditDes());
        assertNull(audit.otherTokens().memoryTokens().getTokenMeta());
        assertCount(audit.otherTokens().attachmentTokens(), 0);
        assertEquals(AuditStatus.SUCCESS, audit.userTokens().getStatus());
    }

    @Test
    void reportsUnsupportedAttachmentsAsFailedInsteadOfPartialOrZeroTotals() {
        var tokenizer = new DefaultTokenizer(new OpenAiTokenizer());
        var execution = execution(List.of(UserMessageEntity.builder().content(List.of(
                ImageContent.from(Image.from("unused")), new PdfFileContent())).build()));

        var audit = assertDoesNotThrow(() -> auditor(tokenizer).analysis(execution));

        assertEquals(AuditStatus.FAILED, audit.otherTokens().attachmentTokens().getStatus());
        assertNull(audit.otherTokens().attachmentTokens().getTokenMeta());
        assertTrue(audit.otherTokens().attachmentTokens().getAuditDes().contains("supports only"));
        assertCount(audit.otherTokens().memoryTokens(), 0);
        assertEquals(AuditStatus.SUCCESS, audit.systemTokens().getStatus());
    }

    @Test
    void missingEstimatorFailsOnlyTheAttachmentDetail() {
        var execution = execution(List.of(UserMessageEntity.from(Image.from("unused"))));

        var audit = assertDoesNotThrow(() -> auditor(new DefaultTokenizer()).analysis(execution));

        assertEquals(AuditStatus.FAILED, audit.otherTokens().attachmentTokens().getStatus());
        assertNull(audit.otherTokens().attachmentTokens().getTokenMeta());
        assertCount(audit.otherTokens().memoryTokens(), 0);
        assertEquals(AuditStatus.SUCCESS, audit.assistantTokens().getStatus());
    }

    @Test
    void definitionLookupFailureDoesNotDiscardMemoryAndAttachmentDetails() {
        DefaultTokenAuditor auditor = new DefaultTokenAuditor(new DefaultTokenizer(),
                AgentConfig.builder().maxTokens(1000).build(), new ToolRegistry(List.of()),
                (java.util.function.Function<Execution, List<ToolDefinition<?>>>) execution -> {
                    throw new IllegalStateException("MCP unavailable");
                });

        var audit = assertDoesNotThrow(() -> auditor.analysis(execution(List.of())));

        assertEquals(AuditStatus.FAILED, audit.otherTokens().mcpToolDefinitionTokens().getStatus());
        assertCount(audit.otherTokens().memoryTokens(), 0);
        assertCount(audit.otherTokens().attachmentTokens(), 0);
    }

    private void assertCount(DocumentElement<Integer> element, int expected) {
        assertEquals(AuditStatus.SUCCESS, element.getStatus());
        assertEquals(expected, element.getTokenMeta());
        assertEquals(expected / 1000.0, element.getRatio());
    }

    @Test
    void countsToolMessagesAsOneBatchAndKeepsTheMcpBucketSeparateFromToolNames() {
        var tokenizer = new DefaultTokenizer(new OpenAiTokenizer());
        DefaultTokenAuditor auditor = auditor(tokenizer);
        var local = ToolMessageEntity.builder().id("local").name("MCP").toolOutput("local result").build();
        var remote = ToolMessageEntity.builder().id("remote").name("unprefixed")
                .isMcpTool(true).toolOutput("remote result").build();
        var skill = ToolMessageEntity.builder().id("skill").name("read_skill")
                .isSkillTool(true).toolOutput("skill result").build();
        String localArguments = "{\"path\":\"document.txt\"}";
        String skillArguments = "{\"name\":\"research\"}";
        var ai = AiMessageEntity.builder().toolCalls(List.of(
                new ToolCallRequest("local", "MCP", 0, localArguments),
                new ToolCallRequest("remote", "unprefixed", 1, null),
                new ToolCallRequest("skill", "read_skill", 2, skillArguments))).build();
        List<Message> messages = List.of(ai, skill, local, remote,
                AiMessageEntity.builder().text("done").build());
        int localArgumentTokens = tokenizer.countText(localArguments);
        int skillArgumentTokens = tokenizer.countText(skillArguments);
        int total = tokenizer.count(List.of(skill, local, remote));

        DocumentElement<ToolAuditDocument> toolAudit = auditor.analysis(execution(messages)).toolTokens();

        assertEquals(AuditStatus.SUCCESS, toolAudit.getStatus());
        assertEquals(total, toolAudit.getTokenMeta().toolTokens());
        assertEquals(total / 1000.0, toolAudit.getRatio());
        assertEquals(Map.of("MCP", new ToolElement(localArgumentTokens,
                tokenizer.countText(local.getToolOutput()), tokenizer.count(local))),
                toolAudit.getTokenMeta().tokenDetails());
        assertEquals(new ToolElement(0, tokenizer.countText(remote.getToolOutput()), tokenizer.count(remote)),
                toolAudit.getTokenMeta().mcpDetail().getTokenMeta());
        assertEquals(new ToolElement(skillArgumentTokens, tokenizer.countText(skill.getToolOutput()),
                tokenizer.count(skill)), toolAudit.getTokenMeta().skillDetail().getTokenMeta());
        assertEquals(tokenizer.count(remote) / 1000.0, toolAudit.getTokenMeta().mcpDetail().getRatio());
        assertEquals(tokenizer.count(skill) / 1000.0,
                toolAudit.getTokenMeta().skillDetail().getRatio());
    }

    @Test
    void matchesRepeatedCallIdsToTheirOwnRound() {
        DefaultTokenAuditor auditor = auditor(new DefaultTokenizer());
        var first = AiMessageEntity.builder().toolCalls(List.of(
                new ToolCallRequest("same", "read", 0, "abc"))).build();
        var second = AiMessageEntity.builder().toolCalls(List.of(
                new ToolCallRequest("same", "read", 0, "abcdef"))).build();
        TokenAuditDocument audit = auditor.analysis(execution(List.of(first,
                ToolMessageEntity.builder().id("same").name("read").toolOutput("one").build(), second,
                ToolMessageEntity.builder().id("same").name("read").toolOutput("two").build())));
        DocumentElement<ToolAuditDocument> result = audit.toolTokens();

        assertEquals(AuditStatus.SUCCESS, result.getStatus());
        assertEquals(new ToolElement(3, 2, 2), result.getTokenMeta().tokenDetails().get("read"));
        assertEquals(2, result.getTokenMeta().toolTokens());
        assertEquals(5, audit.assistantTokens().getTokenMeta());
        assertEquals(7, audit.assistantTokens().getTokenMeta() + result.getTokenMeta().toolTokens());
    }

    @Test
    void reportsMissingCallsInsteadOfCountingUnknownInputAsZero() {
        DefaultTokenAuditor auditor = auditor(new DefaultTokenizer());
        TokenAuditDocument audit = auditor.analysis(execution(List.of(
                ToolMessageEntity.builder().id("missing").name("read").toolOutput("result").build())));

        assertEquals(AuditStatus.FAILED, audit.toolTokens().getStatus());
        assertTrue(audit.toolTokens().getAuditDes().contains("Missing tool call"));
        assertEquals(AuditStatus.SUCCESS, audit.assistantTokens().getStatus());
    }

    @Test
    void distinguishesZeroMissingDataAndFailedEstimates() {
        var tokenizer = new DefaultTokenizer() {
            @Override
            public ContextUsageMetric usage(List<Message> messages, List<com.summit.core.tool.ToolDefinition<?>> tools, Integer maxTokens) {
                if (!messages.isEmpty() && messages.getFirst() instanceof UserMessageEntity) {
                    throw new IllegalStateException("estimate failed");
                }
                return super.usage(messages, tools, maxTokens);
            }
        };
        DefaultTokenAuditor auditor = auditor(tokenizer);

        DocumentElement<Integer> empty = auditor.analysis(execution(List.of())).userTokens();
        assertEquals(AuditStatus.SUCCESS, empty.getStatus());
        assertEquals(0, empty.getTokenMeta());

        TokenAuditDocument missing = auditor.analysis(execution(null));
        assertEquals(AuditStatus.NO_DATA, missing.userTokens().getStatus());
        assertEquals(AuditStatus.NO_DATA, missing.toolTokens().getStatus());
        assertNull(missing.userTokens().getTokenMeta());

        TokenAuditDocument audit = auditor.analysis(execution(List.of(UserMessageEntity.from("task"))));
        assertEquals(AuditStatus.FAILED, audit.userTokens().getStatus());
        assertEquals("estimate failed", audit.userTokens().getAuditDes());
        assertNull(audit.userTokens().getTokenMeta());
        assertEquals(AuditStatus.SUCCESS, audit.systemTokens().getStatus());
        assertEquals(AuditStatus.SUCCESS, audit.toolTokens().getStatus());
    }

    @Test
    void countsOnlyWhitelistedDefinitionsWithoutIncludingMessageHistory() {
        DefaultTokenizer tokenizer = new DefaultTokenizer(new OpenAiTokenizer());
        ToolDefinition<ToolExecutor> allowed = definition("lookup");
        ToolDefinition<ToolExecutor> excluded = definition("excluded");
        DefaultTokenAuditor auditor = new DefaultTokenAuditor(tokenizer,
                AgentConfig.builder().maxTokens(1000).build(),
                new ToolRegistry(List.of(allowed, excluded)), McpToolScope.EMPTY);
        Execution execution = execution(List.of(UserMessageEntity.from("long history ".repeat(1000))));
        execution.getAgentRequest().setToolList(List.of("lookup"));

        TokenAuditDocument audit = auditor.analysis(execution);
        DocumentElement<Integer> definitions = audit.otherTokens().toolDefinitionTokens();
        int expected = tokenizer.count(List.of(), List.of(allowed));

        assertTrue(expected > 0);
        assertEquals(AuditStatus.SUCCESS, definitions.getStatus());
        assertEquals(expected, definitions.getTokenMeta());
        assertEquals(expected / 1000.0, definitions.getRatio());
        assertEquals(0, audit.otherTokens().mcpToolDefinitionTokens().getTokenMeta());
        execution.setMessages(List.of(UserMessageEntity.from("short")));
        assertEquals(expected, auditor.analysis(execution).otherTokens().toolDefinitionTokens().getTokenMeta());
        execution.getAgentRequest().setToolList(List.of());
        assertEquals(0, auditor.analysis(execution).otherTokens().toolDefinitionTokens().getTokenMeta());
    }

    @Test
    void countsOnlyDisclosedMcpDefinitions() {
        ToolDefinition<ToolExecutor> disclosed = definition("mcp_visible");
        ToolDefinition<ToolExecutor> undisclosed = definition("mcp_hidden");
        McpSession session = new McpSession() {
            @Override
            public String name() { return "audit-server"; }

            @Override
            public List<ToolDefinition<? extends ToolExecutor>> tools() {
                return List.of(disclosed, undisclosed);
            }

            @Override
            public void close() { }
        };
        DefaultTokenizer tokenizer = new DefaultTokenizer(new OpenAiTokenizer());
        try (McpToolScope scope = McpToolScope.of(List.of(session))) {
            scope.disclose(List.of(disclosed.name()));
            DefaultTokenAuditor auditor = new DefaultTokenAuditor(tokenizer,
                    AgentConfig.builder().maxTokens(1000).build(), new ToolRegistry(List.of()), scope);

            TokenAuditDocument audit = auditor.analysis(execution(List.of(UserMessageEntity.from("task"))));

            int expected = tokenizer.count(List.of(), List.of(disclosed));
            assertEquals(0, audit.otherTokens().toolDefinitionTokens().getTokenMeta());
            assertEquals(AuditStatus.SUCCESS, audit.otherTokens().mcpToolDefinitionTokens().getStatus());
            assertEquals(expected, audit.otherTokens().mcpToolDefinitionTokens().getTokenMeta());
            assertEquals(expected / 1000.0, audit.otherTokens().mcpToolDefinitionTokens().getRatio());
        }
    }

    private DefaultTokenAuditor auditor(DefaultTokenizer tokenizer) {
        return new DefaultTokenAuditor(tokenizer, AgentConfig.builder().maxTokens(1000).build(),
                new ToolRegistry(List.of()), McpToolScope.EMPTY);
    }

    private Execution execution(List<Message> messages) {
        return Execution.builder().agentRequest(AgentRequest.builder().contextSpec(ContextSpec.builder().context(messages).build()).build())
                .messages(messages).build();
    }

    private ToolDefinition<ToolExecutor> definition(String name) {
        return ToolDefinition.<ToolExecutor>builder().id(name).name(name).description("Find a file")
                .parametersJsonSchema("{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"}}}")
                .maxOutput(64).timeout(5L).concurrentPolicy(ConcurrentPolicy.READ_ONLY)
                .executor(ignored -> ToolExecuteResult.success("ok")).build();
    }
}
