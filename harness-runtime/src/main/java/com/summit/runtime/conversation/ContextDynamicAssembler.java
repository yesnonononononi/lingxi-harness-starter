package com.summit.runtime.conversation;

import com.summit.core.agent.ContextSpec;
import com.summit.core.agent.ToolSpec;
import com.summit.core.conversation.message.ContextSectionKind;
import com.summit.core.conversation.message.ContextSectionMark;
import com.summit.core.conversation.message.Message;
import com.summit.core.conversation.message.UserMessageEntity;
import com.summit.core.mcp.McpResume;
import com.summit.core.mcp.McpToolScope;
import com.summit.core.prompt.EnvironmentFormatter;
import com.summit.core.skill.SkillResume;
import com.summit.core.workspace.WorkspaceAccessMode;
import com.summit.core.workspace.WorkspaceOptions;
import lombok.Builder;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
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
    public static final String CLEARED_BODY = "(none)";

    private static final String MCP_TITLE = "\n\n## MCP Tools\n";
    private static final String SKILL_TITLE = "\n\n## Skill Prompt\n";
    private static final String MEMORY_TITLE = "\n\n## Memory Prompt\n";
    private static final String WORK_DIR_TITLE = "\n\n## Working Directory\n";
    private static final String PERMISSION_TITLE = "\n\n## Workspace Permission\n";
    private static final String ENVIRONMENT_TITLE = "\n\n## Workspace Environment\n";
    private static final String FORBIDDEN_TOOLS_TITLE = "\n\n## Forbidden Tools\n";
    private static final String TASK_TITLE = "\n\n## Task Manifest\n";


    private static final int MAX_DESC_LENGTH = 300;



    /**
     * Renders all non-empty dynamic sections (including volatile workspace options and tool
     * blacklist) into one {@code <context>} message, or {@code null} when nothing is present.
     *
     * <p>The one call for "give me this round's block". {@link #renderSections} and
     * {@link #assembleSections} stay separate for the runtime, which has to diff the sections before
     * rendering anything — not an overload of this method, but the two halves it is built from.</p>
     */
    @Builder(builderMethodName = "builder", builderClassName = "AssembleBuilder")
    public static @Nullable UserMessageEntity assemble(ContextSpec contextSpec, McpToolScope scope,
                                                       List<SkillResume> skills,
                                                       @Nullable WorkspaceOptions workspaceOptions,
                                                       @Nullable ToolSpec toolSpec,
                                                       List<String> tasks) {
        return assembleSections(renderSections(contextSpec, scope, skills, workspaceOptions, toolSpec, tasks));
    }

    /** Renders each non-empty dynamic category into its section body, keyed by {@link ContextSectionKind}. */
    @Builder(builderMethodName = "sectionsBuilder", builderClassName = "SectionsBuilder")
    public static Map<ContextSectionKind, String> renderSections(ContextSpec contextSpec, McpToolScope scope,
                                                                 List<SkillResume> skills,
                                                                 @Nullable WorkspaceOptions workspaceOptions,
                                                                 @Nullable ToolSpec toolSpec,
                                                                 List<String> tasks) {
        Map<ContextSectionKind, String> sections = new EnumMap<>(ContextSectionKind.class);
        putIfPresent(sections, ContextSectionKind.MCP, withMcpToolPrompt(scope));
        putIfPresent(sections, ContextSectionKind.SKILL, withSkillPrompt(skills));
        putIfPresent(sections, ContextSectionKind.MEMORY, withMemoryPrompt(contextSpec == null ? null : contextSpec.getMemory()));
        putIfPresent(sections, ContextSectionKind.WORK_DIR, withWorkDirPrompt(workspaceOptions));
        putIfPresent(sections, ContextSectionKind.PERMISSION, withPermissionPrompt(workspaceOptions));
        putIfPresent(sections, ContextSectionKind.ENVIRONMENT, withEnvironmentPrompt(workspaceOptions));
        putIfPresent(sections, ContextSectionKind.FORBIDDEN_TOOLS, withForbiddenToolsPrompt(toolSpec));
        putIfPresent(sections, ContextSectionKind.TASK, withTaskPrompt(tasks));
        return sections;
    }

    /** Assembles the given section bodies in canonical {@link ContextSectionKind} order, or {@code null} if empty. */
    public static @Nullable UserMessageEntity assembleSections(Map<ContextSectionKind, String> bodies) {
        if (bodies == null || bodies.isEmpty()) {
            return null;
        }
        StringBuilder text = new StringBuilder(LABEL);
        List<ContextSectionMark> marks = new ArrayList<>(bodies.size());
        for (ContextSectionKind kind : ContextSectionKind.values()) {
            String body = bodies.get(kind);
            if (body == null || body.isBlank()) {
                continue;
            }
            text.append(titleFor(kind));
            int start = text.length();
            text.append(body);
            marks.add(new ContextSectionMark(kind, start, body.length()));
        }
        if (marks.isEmpty()) {
            return null;
        }
        return UserMessageEntity.from(text.toString()).markContextContract().withContextSections(marks);
    }

    /**
     * Collects the latest rendered body for each {@link ContextSectionKind} from {@code messages[0..endExclusive)}.
     * Shared by per-round diffing and compaction merging so baseline extraction lives in one place.
     */
    public static Map<ContextSectionKind, String> latestSections(List<Message> messages, int endExclusive) {
        Map<ContextSectionKind, String> latest = new EnumMap<>(ContextSectionKind.class);
        int limit = Math.min(endExclusive, messages.size());
        for (int i = 0; i < limit; i++) {
            if (!(messages.get(i) instanceof UserMessageEntity user) || !user.hasContextContract()) {
                continue;
            }
            String text = user.text();
            for (ContextSectionMark mark : user.contextSections()) {
                int end = mark.start() + mark.length();
                if (mark.start() >= 0 && end <= text.length()) {
                    latest.put(mark.kind(), text.substring(mark.start(), end));
                }
            }
        }
        return latest;
    }

    private static void putIfPresent(Map<ContextSectionKind, String> sections, ContextSectionKind kind,
                                     @Nullable String body) {
        if (body != null && !body.isBlank()) {
            sections.put(kind, body);
        }
    }

    private static String titleFor(ContextSectionKind kind) {
        return switch (kind) {
            case MCP -> MCP_TITLE;
            case SKILL -> SKILL_TITLE;
            case MEMORY -> MEMORY_TITLE;
            case WORK_DIR -> WORK_DIR_TITLE;
            case PERMISSION -> PERMISSION_TITLE;
            case ENVIRONMENT -> ENVIRONMENT_TITLE;
            case FORBIDDEN_TOOLS -> FORBIDDEN_TOOLS_TITLE;
            case TASK -> TASK_TITLE;
        };
    }

    private static @Nullable String withForbiddenToolsPrompt(@Nullable ToolSpec toolSpec) {
        if (toolSpec == null || toolSpec.getForbiddenTools() == null || toolSpec.getForbiddenTools().isEmpty()) {
            return null;
        }
        List<String> names = toolSpec.getForbiddenTools().stream()
                .filter(Objects::nonNull)
                .map(String::strip)
                .filter(name -> !name.isEmpty())
                .toList();
        return names.isEmpty() ? null : String.join(", ", names);
    }

    private static @Nullable String withWorkDirPrompt(@Nullable WorkspaceOptions options) {
        String workDir = options == null ? null : options.getWorkDir();
        return workDir == null || workDir.isBlank() ? null : workDir.strip();
    }

    private static @Nullable String withPermissionPrompt(@Nullable WorkspaceOptions options) {
        WorkspaceAccessMode mode = options == null ? null : options.getAccessMode();
        return mode == null ? null : "Access mode: " + mode.name();
    }

    private static @Nullable String withEnvironmentPrompt(@Nullable WorkspaceOptions options) {
        return options == null ? null : EnvironmentFormatter.formatEntries(options.getEnvironment(), "");
    }

    private static String withSkillPrompt(List<SkillResume> skills) {
        if (skills == null || skills.isEmpty()) return null;
        StringBuilder stringBuilder = new StringBuilder();

        stringBuilder.append("Before using a skill, call `read_skill` if you have  with its entry path below. ")
                .append("Paths refer to the host-side Skill resources, independently of the execution workspace. ")
                .append("Read referenced files with the same tool, resolving references against the directory of the file that contains them.\n");

        for (SkillResume skill : skills) {
            stringBuilder.append("\n- ").append(skill.name()).append(": ").append(descriptionTruncate(skill.description()));
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
                They are never declared as tools of their own and their parameter schemas are deliberately
                left out of this prompt: call `list_mcp_tools` with a server name to list that server's tools
                (name and description), or `search_tool` with a keyword to get one tool's schema directly,
                then call it through `call_mcp_tool`. A server holding more tools than one listing shows is
                listed in successive windows: pass `start` and `end` to read a later one, and keep going while
                the result reports `hasMore`.
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
        String description = descriptionTruncate(mcp.description());
        return description.isEmpty() ? "no description" : description;
    }

    /**
     * Normalizes whitespace first so leading/repeated blanks do not consume the length budget,
     * then truncates to {@link #MAX_DESC_LENGTH} without splitting a UTF-16 surrogate pair.
     */
    private static String descriptionTruncate(@Nullable String desc) {
        String normalized = normalize(desc);
        if (normalized.length() <= MAX_DESC_LENGTH) {
            return normalized;
        }
        int end = MAX_DESC_LENGTH;
        if (Character.isHighSurrogate(normalized.charAt(end - 1))) {
            end--;
        }
        return normalized.substring(0, end);
    }

    /**
     * Collapses a multi-line description to one line so a résumé entry cannot break the list.
     */
    private static String normalize(@Nullable String text) {
        return text == null ? "" : text.strip().replaceAll("\\s+", " ");
    }

}
