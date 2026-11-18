package com.summit.runtime.conversation;

import com.summit.core.agent.ContextSpec;
import com.summit.core.conversation.message.ContextSectionKind;
import com.summit.core.conversation.message.ContextSectionMark;
import com.summit.core.conversation.message.UserMessageEntity;
import com.summit.core.mcp.McpSession;
import com.summit.core.mcp.McpToolScope;
import com.summit.core.skill.SkillResume;
import com.summit.core.tool.ConcurrentPolicy;
import com.summit.core.tool.ToolDefinition;
import com.summit.core.tool.ToolExecuteResult;
import com.summit.core.tool.ToolExecutor;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The rendered text is a cached prefix for the next rounds, so it must stay byte-identical; the
 * section marks must point exactly at the bodies, because the audit counts tokens through them.
 */
class ContextDynamicAssemblerSectionsTest {

    private static final String MCP_TITLE = "\n\n## MCP Tools\n";
    private static final String SKILL_TITLE = "\n\n## Skill Prompt\n";
    private static final String MEMORY_TITLE = "\n\n## Memory Prompt\n";
    private static final String TASK_TITLE = "\n\n## Task Manifest\n";

    private static final String MCP_BODY = """
            Remote tools are hosted by MCP servers; the counts below are how many each one contributed.
            They are never declared as tools of their own and their parameter schemas are deliberately
            left out of this prompt: call `list_mcp_tools` with a server name to list that server's tools
            (name and description), or `search_tool` with a keyword to get one tool's schema directly,
            then call it through `call_mcp_tool`. A server holding more tools than one listing shows is
            listed in successive windows: pass `start` and `end` to read a later one, and keep going while
            the result reports `hasMore`.
            """;

    private static final String MCP_SECTION_BODY = MCP_BODY + "\n- github - GitHub remote tools - 1 tools";

    private static final String SKILL_BODY =
            "Before using a skill, call `read_skill` if you have  with its entry path below. "
                    + "Paths refer to the host-side Skill resources, independently of the execution workspace. "
                    + "Read referenced files with the same tool, resolving references against the directory of the file that contains them.\n"
                    + "\n- demo: Demo skill"
                    + "\n  Entry path: /skills/demo/SKILL.md";

    @Test
    void rendersTheLegendAndSectionsInRenderOrder() {
        UserMessageEntity message = assembleAllSections();

        // The legend is part of every block: it is what tells the model the newest block wins.
        String expected = "<context>\n" + ContextDynamicAssembler.LEGEND
                + MCP_TITLE + MCP_SECTION_BODY
                + SKILL_TITLE + SKILL_BODY
                + MEMORY_TITLE + "mem line"
                + TASK_TITLE + "do the thing";

        assertEquals(expected, message.text(), "the cached prefix must not move");
    }

    @Test
    void marksEachSectionBodyInRenderOrder() {
        UserMessageEntity message = assembleAllSections();

        List<ContextSectionMark> marks = message.contextSections();
        assertEquals(List.of(ContextSectionKind.MCP, ContextSectionKind.SKILL,
                        ContextSectionKind.MEMORY, ContextSectionKind.TASK),
                marks.stream().map(ContextSectionMark::kind).toList());

        assertEquals(MCP_SECTION_BODY, bodyOf(message, marks.get(0)));
        assertEquals(SKILL_BODY, bodyOf(message, marks.get(1)));
        assertEquals("mem line", bodyOf(message, marks.get(2)));
        assertEquals("do the thing", bodyOf(message, marks.get(3)));
    }

    @Test
    void marksDoNotOverlapAndSkipTheHeadings() {
        UserMessageEntity message = assembleAllSections();
        List<ContextSectionMark> marks = message.contextSections();

        int previousEnd = 0;
        for (ContextSectionMark mark : marks) {
            assertTrue(mark.start() > previousEnd, "a heading separates the bodies of two sections");
            previousEnd = mark.start() + mark.length();
        }
        assertTrue(previousEnd <= message.text().length(), "every span lies inside the message text");
    }

    @Test
    void oneSectionYieldsOneMark() {
        UserMessageEntity message = ContextDynamicAssembler.builder()
                .contextSpec(ContextSpec.builder().memory(List.of("only memory")).build())
                .scope(McpToolScope.EMPTY)
                .skills(List.of())
                .tasks(List.of())
                .build();

        assertNotNull(message);
        assertEquals(1, message.contextSections().size());
        assertEquals(ContextSectionKind.MEMORY, message.contextSections().getFirst().kind());
        assertEquals("<context>\n" + ContextDynamicAssembler.LEGEND + MEMORY_TITLE + "only memory",
                message.text());
    }

    @Test
    void noSectionYieldsNoMessage() {
        assertNull(ContextDynamicAssembler.builder()
                .contextSpec(ContextSpec.builder().memory(List.of()).build())
                .scope(McpToolScope.EMPTY)
                .skills(List.of())
                .tasks(List.of())
                .build());
    }

    @Test
    void nullSkillDescriptionDoesNotThrow() {
        UserMessageEntity message = ContextDynamicAssembler.builder()
                .skills(List.of(new SkillResume("demo", null, "/skills/demo/SKILL.md")))
                .build();

        assertNotNull(message);
        assertTrue(message.text().contains("\n- demo: \n  Entry path: /skills/demo/SKILL.md"));
    }

    @Test
    void normalizesWhitespaceBeforeTruncatingDescriptions() {
        String longContent = "   \n  " + "a".repeat(295) + "   \t  " + "b".repeat(50);
        UserMessageEntity message;
        try (McpToolScope scope = scopeOf(List.of(remoteTool("mcp_github_get_me", "desc")), longContent)) {
            message = ContextDynamicAssembler.builder()
                    .scope(scope)
                    .skills(List.of(new SkillResume("demo", longContent, "/skills/demo/SKILL.md")))
                    .build();
        }

        assertNotNull(message);
        String expectedDesc = "a".repeat(295) + " " + "b".repeat(4);
        assertEquals(300, expectedDesc.length());
        assertTrue(message.text().contains("\n- demo: " + expectedDesc + "\n"));
        assertTrue(message.text().contains("\n- github - " + expectedDesc + " - 1 tools"));
    }

    private static String bodyOf(UserMessageEntity message, ContextSectionMark mark) {
        return message.text().substring(mark.start(), mark.start() + mark.length());
    }

    private static UserMessageEntity assembleAllSections() {
        UserMessageEntity message;
        try (McpToolScope scope = scopeOf(List.of(remoteTool("mcp_github_get_me", "Get the GitHub user")))) {
            message = ContextDynamicAssembler.builder()
                    .contextSpec(ContextSpec.builder().memory(List.of("mem line")).build())
                    .scope(scope)
                    .skills(List.of(new SkillResume("demo", "Demo skill", "/skills/demo/SKILL.md")))
                    .tasks(List.of("do the thing"))
                    .build();
        }
        assertNotNull(message);
        return message;
    }

    private static ToolDefinition<?> remoteTool(String name, String description) {
        return ToolDefinition.builder()
                .id(name).name(name).maxOutput(2_000).timeout(20L)
                .description(description)
                .parametersJsonSchema("{\"type\":\"object\",\"properties\":{\"owner\":{\"type\":\"string\"}}}")
                .concurrentPolicy(ConcurrentPolicy.READ_ONLY)
                .executor(execution -> ToolExecuteResult.success("ok"))
                .build();
    }

    private static McpToolScope scopeOf(List<ToolDefinition<?>> tools) {
        return scopeOf(tools, "GitHub remote tools");
    }

    private static McpToolScope scopeOf(List<ToolDefinition<?>> tools, String description) {
        List<ToolDefinition<? extends ToolExecutor>> scoped = new ArrayList<>(tools);
        return McpToolScope.of(List.of(new McpSession() {
            @Override
            public String name() {
                return "github";
            }

            @Override
            public String description() {
                return description;
            }

            @Override
            public List<ToolDefinition<? extends ToolExecutor>> tools() {
                return scoped;
            }

            @Override
            public void close() {
            }
        }));
    }
}
