package com.summit.runtime.audit;

import com.summit.core.agent.AgentRequest;
import com.summit.core.agent.ContextSpec;
import com.summit.core.agent.Execution;
import com.summit.core.audit.AuditStatus;
import com.summit.core.audit.DocumentElement;
import com.summit.core.conversation.message.ContextSectionKind;
import com.summit.core.conversation.message.Message;
import com.summit.core.conversation.message.UserMessageEntity;
import com.summit.core.mcp.McpToolScope;
import com.summit.core.skill.SkillResume;
import com.summit.core.tool.ToolRegistry;
import com.summit.runtime.agent.AgentConfig;
import com.summit.runtime.conversation.ContextDynamicAssembler;
import com.summit.runtime.conversation.DefaultTokenizer;
import openAi.OpenAiTokenizer;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DefaultTokenAuditorDynamicSectionsTest {

    private final DefaultTokenizer tokenizer = new DefaultTokenizer(new OpenAiTokenizer());

    @Test
    void reportsEverySectionAsItsOwnSlice() {
        UserMessageEntity context = ContextDynamicAssembler.assemble(
                ContextSpec.builder().memory(List.of("mem line")).build(),
                McpToolScope.EMPTY,
                List.of(new SkillResume("demo", "Demo skill", "/skills/demo/SKILL.md")),
                List.of("do the thing"));
        Execution execution = execution(List.of(context), List.of("mem line"));

        var sections = auditor().analysis(execution).otherTokens().dynamicContextSections();

        assertEquals(List.of(ContextSectionKind.SKILL.name(), ContextSectionKind.MEMORY.name(),
                        ContextSectionKind.TASK.name()),
                sections.stream().map(DocumentElement::getType).toList());
        for (DocumentElement<Integer> section : sections) {
            assertEquals(AuditStatus.SUCCESS, section.getStatus());
        }
        assertEquals(tokenizer.countText("mem line"), sections.get(1).getTokenMeta());
        assertEquals(tokenizer.countText("do the thing"), sections.get(2).getTokenMeta());
    }

    @Test
    void sectionsStayBelowTheWholeBlockBecauseHeadingsAndBoundariesCostTokens() {
        UserMessageEntity context = ContextDynamicAssembler.assemble(
                ContextSpec.builder().memory(List.of("mem line one", "mem line two")).build(),
                McpToolScope.EMPTY,
                List.of(new SkillResume("demo", "Demo skill", "/skills/demo/SKILL.md")),
                List.of("do the thing", "and another one"));
        Execution execution = execution(List.of(context), List.of("mem line one", "mem line two"));

        var otherTokens = auditor().analysis(execution).otherTokens();
        int sections = otherTokens.dynamicContextSections().stream()
                .mapToInt(DocumentElement::getTokenMeta).sum();

        assertTrue(sections < otherTokens.dynamicContextTokens().getTokenMeta(),
                "the section slices are not additive: headings belong to the whole block only");
    }

    @Test
    void memoryFollowsTheMessageNotTheRequest() {
        UserMessageEntity taskOnly = ContextDynamicAssembler.assemble(
                ContextSpec.builder().build(), McpToolScope.EMPTY, List.of(), List.of("do the thing"));
        Execution execution = execution(List.of(taskOnly), List.of("memory the request still carries"));

        var otherTokens = auditor().analysis(execution).otherTokens();

        assertEquals(0, otherTokens.memoryTokens().getTokenMeta(),
                "memory is read from the block that goes out, not re-derived from the request");
        assertTrue(otherTokens.dynamicContextSections().stream()
                .noneMatch(section -> ContextSectionKind.MEMORY.name().equals(section.getType())));
    }

    @Test
    void aBrokenSliceFailsAlone() {
        DefaultTokenizer failing = new DefaultTokenizer() {
            @Override
            public int countText(String text) {
                if ("do the thing".equals(text)) throw new IllegalStateException("slice failed");
                return super.countText(text);
            }
        };
        UserMessageEntity context = ContextDynamicAssembler.assemble(
                ContextSpec.builder().memory(List.of("mem line")).build(), McpToolScope.EMPTY,
                List.of(), List.of("do the thing"));
        Execution execution = execution(List.of(context), List.of("mem line"));

        var otherTokens = auditor(failing).analysis(execution).otherTokens();

        assertEquals(AuditStatus.FAILED, otherTokens.dynamicContextSections().get(1).getStatus());
        assertEquals(AuditStatus.SUCCESS, otherTokens.memoryTokens().getStatus());
        assertEquals(tokenizer.countText("mem line"), otherTokens.memoryTokens().getTokenMeta());
    }

    private DefaultTokenAuditor auditor() {
        return auditor(tokenizer);
    }

    private DefaultTokenAuditor auditor(DefaultTokenizer tokenizer) {
        return new DefaultTokenAuditor(tokenizer, AgentConfig.builder().maxTokens(1000).build(),
                new ToolRegistry(List.of()), McpToolScope.EMPTY);
    }

    private Execution execution(List<Message> messages, List<String> requestMemory) {
        return Execution.builder()
                .agentRequest(AgentRequest.builder().contextSpec(
                        ContextSpec.builder().context(messages).memory(requestMemory).build()).build())
                .messages(messages)
                .build();
    }
}
