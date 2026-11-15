package com.summit.runtime.conversation;

import com.summit.core.agent.ContextSpec;
import com.summit.core.conversation.message.ContextSectionKind;
import com.summit.core.conversation.message.ContextSectionMark;
import com.summit.core.conversation.message.UserMessageEntity;
import com.summit.core.mcp.McpResume;
import com.summit.core.mcp.McpToolScope;
import com.summit.core.skill.SkillResume;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Renders everything that changes from round to round — MCP résumés, skills, memory and the task
 * manifest — as one user message.
 *
 * <p>These sections used to live in the leading system message. They change far more often than the
 * system prompt does, so keeping them there invalidated the prompt cache (system prompt + tool
 * definitions) on every change. Parked in a user message they sit <em>behind</em> the cached
 * prefix, and the client only pays for the tail.</p>
 *
 * <p>Sections keep their own line structure: the model reads headings and list items, and folding
 * the whole document onto one line — which is what a blanket whitespace normalisation used to do —
 * throws that structure away. Only single-line fields (a description inside a list entry) are
 * collapsed, so one entry cannot break the list.</p>
 *
 * <p>Each section is also marked with the span its body occupies in the rendered text, so the audit
 * can re-count it on its own boundary instead of reporting the block as one opaque figure.</p>
 */
public class ContextDynamicAssembler {
    public static final String LABEL = "<context>";

    private static final String MCP_TITLE = "\n\n## MCP Tools\n";
    private static final String SKILL_TITLE = "\n\n## Skill Prompt\n";
    private static final String MEMORY_TITLE = "\n\n## Memory Prompt\n";
    private static final String TASK_TITLE = "\n\n## Task Manifest\n";

    private record Section(ContextSectionKind kind, String title, String body) {
    }

    /**
     * The dynamic context of one round, or {@code null} when this request carries no dynamic
     * section at all — a bare-model request must not get an empty user message injected.
     */
    public static @Nullable UserMessageEntity assemble(ContextSpec contextSpec, McpToolScope scope, List<SkillResume> skills, List<String> tasks) {
        List<Section> sections = new ArrayList<>(4);
        addSection(sections, ContextSectionKind.MCP, MCP_TITLE, withMcpToolPrompt(scope));
        addSection(sections, ContextSectionKind.SKILL, SKILL_TITLE, withSkillPrompt(skills));
        addSection(sections, ContextSectionKind.MEMORY, MEMORY_TITLE, withMemoryPrompt(contextSpec.getMemory()));
        addSection(sections, ContextSectionKind.TASK, TASK_TITLE, withTaskPrompt(tasks));
        if (sections.isEmpty()) {
            return null;
        }

        StringBuilder text = new StringBuilder(LABEL);
        List<ContextSectionMark> marks = new ArrayList<>(sections.size());
        for (Section section : sections) {
            text.append(section.title());
            int start = text.length();
            text.append(section.body());
            marks.add(new ContextSectionMark(section.kind(), start, section.body().length()));
        }
        return UserMessageEntity.from(text.toString()).markContextContract().withContextSections(marks);
    }

    private static void addSection(List<Section> sections, ContextSectionKind kind, String title,
                                   @Nullable String body) {
        if (body != null && !body.isBlank()) {
            sections.add(new Section(kind, title, body));
        }
    }

    private static String withSkillPrompt(List<SkillResume> skills) {
        if (skills == null || skills.isEmpty()) return null;
        StringBuilder stringBuilder = new StringBuilder();

        stringBuilder.append("Before using a skill, call `read_skill` if you have  with its entry path below. ")
                .append("Paths refer to the host-side Skill resources, independently of the execution workspace. ")
                .append("Read referenced files with the same tool, resolving references against the directory of the file that contains them.\n");

        for (SkillResume skill : skills) {
            stringBuilder.append("\n- ").append(skill.name()).append(": ").append(normalize(skill.description()));
            if (skill.path() != null && !skill.path().isBlank()) {
                stringBuilder.append("\n  Entry path: ").append(skill.path());
            }
        }

        return stringBuilder.toString();
    }

    private static String withMemoryPrompt(List<String> memory) {
        if (memory == null || memory.isEmpty()) return null;
        List<String> bodies = memory.stream().filter(Objects::nonNull)
                .filter(body -> !body.isBlank()).toList();
        if (bodies.isEmpty()) return null;
        return String.join("\n", bodies);
    }

    private static String withMcpToolPrompt(@Nullable McpToolScope scope) {
        if (scope == null) {
            return null;
        }
        List<McpResume> mcps = scope.resumes();

        if (mcps.isEmpty()) {
            return null;
        }

        StringBuilder stringBuilder = new StringBuilder();

        stringBuilder.append("""
                Remote tools are hosted by MCP servers; the counts below are how many each one contributed.
                Their parameter schemas are deliberately left out of this prompt. Call `list_mcp_tools` with
                a server name to list that server's tools (name and description), or `search_tool` with a
                keyword to get one tool's schema directly. A tool found either way becomes callable from your
                next turn on. A server holding more tools than one listing shows is listed in successive
                windows: pass `start` and `end` to read a later one, and keep going while the result reports
                `hasMore`.
                """);
        for (McpResume mcp : mcps) {
            stringBuilder.append("\n- ").append(mcp.name())
                    .append(" - ").append(descriptionOf(mcp))
                    .append(" - ").append(mcp.toolCount()).append(" tools");
        }
        return stringBuilder.toString();
    }

    private static String withTaskPrompt(List<String> task) {
        if (task == null || task.isEmpty()) {
            return null;
        }
        List<String> lines = task.stream()
                .filter(Objects::nonNull)
                .map(String::strip)
                .filter(line -> !line.isEmpty())
                .toList();
        if (lines.isEmpty()) {
            return null;
        }
        return String.join("\n", lines);
    }


    /**
     * One server's declared description, or a placeholder when its configuration declares none.
     */
    private static String descriptionOf(McpResume mcp) {
        String description = normalize(mcp.description());
        return description.isEmpty() ? "no description" : description;
    }

    /**
     * Collapses a multi-line description to one line so a résumé entry cannot break the list.
     */
    private static String normalize(@Nullable String text) {
        return text == null ? "" : text.strip().replaceAll("\\s+", " ");
    }

}
