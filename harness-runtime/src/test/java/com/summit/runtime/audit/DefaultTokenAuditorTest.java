package com.summit.runtime.audit;

import com.summit.core.agent.AgentRequest;
import com.summit.core.agent.Execution;
import com.summit.core.audit.AuditStatus;
import com.summit.core.audit.DocumentElement;
import com.summit.core.audit.token.TokenAuditDocument;
import com.summit.core.audit.token.tool.ToolAuditDocument;
import com.summit.core.audit.token.tool.ToolElement;
import com.summit.core.compact.ContextUsageMetric;
import com.summit.core.conversation.api.ToolCallRequest;
import com.summit.core.conversation.message.AiMessageEntity;
import com.summit.core.conversation.message.Message;
import com.summit.core.conversation.message.ToolMessageEntity;
import com.summit.core.conversation.message.UserMessageEntity;
import com.summit.core.mcp.McpSession;
import com.summit.core.mcp.McpToolScope;
import com.summit.core.tool.ConcurrentPolicy;
import com.summit.core.tool.ToolDefinition;
import com.summit.core.tool.ToolExecuteResult;
import com.summit.core.tool.ToolExecutor;
import com.summit.core.tool.ToolRegistry;
import com.summit.runtime.agent.AgentConfig;
import com.summit.runtime.conversation.DefaultTokenizer;
import openAi.OpenAiTokenizer;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultTokenAuditorTest {
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
        return Execution.builder().agentRequest(AgentRequest.builder().messages(messages).build())
                .messages(messages).build();
    }

    private ToolDefinition<ToolExecutor> definition(String name) {
        return ToolDefinition.<ToolExecutor>builder().id(name).name(name).description("Find a file")
                .parametersJsonSchema("{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"}}}")
                .maxOutput(64).timeout(5L).concurrentPolicy(ConcurrentPolicy.READ_ONLY)
                .executor(ignored -> ToolExecuteResult.success("ok")).build();
    }
}
