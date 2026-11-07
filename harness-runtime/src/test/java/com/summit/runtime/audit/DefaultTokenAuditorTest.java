package com.summit.runtime.audit;

import com.summit.core.audit.AuditStatus;
import com.summit.core.audit.token.tool.ToolElement;
import com.summit.core.compact.ContextUsageMetric;
import com.summit.core.conversation.api.ToolCallRequest;
import com.summit.core.conversation.message.AiMessageEntity;
import com.summit.core.conversation.message.Message;
import com.summit.core.conversation.message.ToolMessageEntity;
import com.summit.core.conversation.message.UserMessageEntity;
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
        var auditor = new DefaultTokenAuditor(tokenizer, AgentConfig.builder().maxTokens(1000).build());
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
        int total = tokenizer.count(List.of(skill, local, remote)) + localArgumentTokens + skillArgumentTokens;

        var toolAudit = auditor.analysis(messages).toolTokens();

        assertEquals(AuditStatus.SUCCESS, toolAudit.getStatus());
        assertEquals(total, toolAudit.getTokenMeta().toolTokens());
        assertEquals(total / 1000.0, toolAudit.getRatio());
        assertEquals(Map.of("MCP", new ToolElement(localArgumentTokens,
                tokenizer.countText(local.getToolOutput()), tokenizer.count(local) + localArgumentTokens)),
                toolAudit.getTokenMeta().tokenDetails());
        assertEquals(new ToolElement(0, tokenizer.countText(remote.getToolOutput()), tokenizer.count(remote)),
                toolAudit.getTokenMeta().mcpDetail().getTokenMeta());
        assertEquals(new ToolElement(skillArgumentTokens, tokenizer.countText(skill.getToolOutput()),
                tokenizer.count(skill) + skillArgumentTokens), toolAudit.getTokenMeta().skillDetail().getTokenMeta());
        assertEquals(tokenizer.count(remote) / 1000.0, toolAudit.getTokenMeta().mcpDetail().getRatio());
        assertEquals((tokenizer.count(skill) + skillArgumentTokens) / 1000.0,
                toolAudit.getTokenMeta().skillDetail().getRatio());
    }

    @Test
    void matchesRepeatedCallIdsToTheirOwnRound() {
        var auditor = new DefaultTokenAuditor(new DefaultTokenizer(), AgentConfig.builder().maxTokens(1000).build());
        var first = AiMessageEntity.builder().toolCalls(List.of(
                new ToolCallRequest("same", "read", 0, "abc"))).build();
        var second = AiMessageEntity.builder().toolCalls(List.of(
                new ToolCallRequest("same", "read", 0, "abcdef"))).build();
        var result = auditor.analysis(List.of(first,
                ToolMessageEntity.builder().id("same").name("read").toolOutput("one").build(), second,
                ToolMessageEntity.builder().id("same").name("read").toolOutput("two").build())).toolTokens();

        assertEquals(AuditStatus.SUCCESS, result.getStatus());
        assertEquals(new ToolElement(3, 2, 5), result.getTokenMeta().tokenDetails().get("read"));
        assertEquals(5, result.getTokenMeta().toolTokens());
    }

    @Test
    void reportsMissingCallsInsteadOfCountingUnknownInputAsZero() {
        var auditor = new DefaultTokenAuditor(new DefaultTokenizer(), AgentConfig.builder().build());
        var audit = auditor.analysis(List.of(
                ToolMessageEntity.builder().id("missing").name("read").toolOutput("result").build()));

        assertEquals(AuditStatus.FAILED, audit.toolTokens().getStatus());
        assertTrue(audit.toolTokens().getAuditDes().contains("Missing tool call"));
        assertEquals(AuditStatus.SUCCESS, audit.assistantTokens().getStatus());
    }

    @Test
    void distinguishesZeroMissingDataAndFailedEstimates() {
        var tokenizer = new DefaultTokenizer() {
            @Override
            public ContextUsageMetric usage(List<Message> messages, Integer maxTokens) {
                if (!messages.isEmpty() && messages.getFirst() instanceof UserMessageEntity) {
                    throw new IllegalStateException("estimate failed");
                }
                return super.usage(messages, maxTokens);
            }
        };
        var auditor = new DefaultTokenAuditor(tokenizer, AgentConfig.builder().maxTokens(1000).build());

        var empty = auditor.analysis(List.of()).userTokens();
        assertEquals(AuditStatus.SUCCESS, empty.getStatus());
        assertEquals(0, empty.getTokenMeta());

        var missing = auditor.analysis(null);
        assertEquals(AuditStatus.NO_DATA, missing.userTokens().getStatus());
        assertEquals(AuditStatus.NO_DATA, missing.toolTokens().getStatus());
        assertNull(missing.userTokens().getTokenMeta());

        var audit = auditor.analysis(List.of(UserMessageEntity.from("task")));
        assertEquals(AuditStatus.FAILED, audit.userTokens().getStatus());
        assertEquals("estimate failed", audit.userTokens().getAuditDes());
        assertNull(audit.userTokens().getTokenMeta());
        assertEquals(AuditStatus.SUCCESS, audit.systemTokens().getStatus());
        assertEquals(AuditStatus.SUCCESS, audit.toolTokens().getStatus());
    }
}
