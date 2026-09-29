package com.summit.runtime.prompt;

import com.summit.core.mcp.McpResume;
import com.summit.core.prompt.PromptAssembler;
import com.summit.core.runtime.RuntimeEnvironment;
import com.summit.core.runtime.workspace.Workspace;
import com.summit.core.skill.SkillResume;
import lombok.Getter;
import org.jspecify.annotations.Nullable;

import java.util.*;


/**
 * Assembles the leading system message of one execution.
 *
 * <p>Sections are appended in the order the caller chains them, and a section whose input is empty
 * is skipped outright: a heading with nothing under it costs tokens and tells the model nothing.
 * That also makes the assembler tolerant of the shapes a request actually arrives in — a root
 * request carries no {@code task}, a bare-model session carries no business prompt.</p>
 */
public class SystemPromptAssembler implements PromptAssembler {
    @Getter
    private String systemPrompt;

    private final StringBuilder stringBuilder = new StringBuilder();
    /**
     * Environment variables listed verbatim before the rest is collapsed into a count.
     */
    private static final int MAX_ENV_ENTRIES = 40;
    /**
     * Value length after which an environment variable value is truncated.
     */
    private static final int MAX_ENV_VALUE_LENGTH = 200;
    /**
     * Key fragments whose value never reaches the model.
     */
    private static final List<String> SENSITIVE_KEY_MARKERS =
            List.of("KEY", "TOKEN", "SECRET", "PASSWORD", "PASSWD", "CREDENTIAL", "PRIVATE");

    /**
     * Starts a fresh assembly over the given workspace.
     *
     * <p>Returns a new assembler rather than mutating this one, so a builder can be reused for
     * another execution without inheriting the previous one's sections. Every append below targets
     * the instance being returned — writing to {@code this} and handing back a different, empty
     * object is how the execution-environment section silently disappeared from every prompt.</p>
     */
    @Override
    public PromptAssembler startWithWorkspace(Workspace workspace) {
        SystemPromptAssembler started = new SystemPromptAssembler();
        if (workspace == null) {
            return started;
        }

        RuntimeEnvironment environment = workspace.runtimeEnvironment();
        started.stringBuilder.append("\n\n## Execution environment")
                .append("\n- Commands run in the assigned workspace environment, not necessarily on the host machine.")
                .append("\n- Reported OS: ").append(text(environment == null ? null : environment.osType()))
                .append("; shell: ").append(text(environment == null ? null : environment.shellType()))
                .append("; charset: ").append(text(environment == null ? null : environment.charset())).append('.')
                .append("\n- Working directory: ").append(workspace.workDir());

        if (environment != null) {
            started.stringBuilder.append(environment.isolated()
                    ? "\n- This workspace is isolated; host tools and files are available only when explicitly exposed."
                    : "\n- This workspace is not isolated; host tools and files are reachable.");
            started.appendEnvironmentVariables(environment.envs());
        }
        return started;
    }

    @Override
    public PromptAssembler withBusinessPrompt(String businessPrompt) {
        if (isBlank(businessPrompt)) {
            return this;
        }
        stringBuilder.append("\n\n## Business Prompt\n").append(businessPrompt.strip());
        return this;
    }

    @Override
    public PromptAssembler withTaskPrompt(List<String> task) {
        if (task == null || task.isEmpty()) {
            return this;
        }
        List<String> lines = task.stream()
                .filter(Objects::nonNull)
                .map(String::strip)
                .filter(line -> !line.isEmpty())
                .toList();
        if (lines.isEmpty()) {
            return this;
        }
        stringBuilder.append("\n\n## Task Manifest\n").append(String.join("\n", lines));
        return this;
    }

    /**
     * Publishes the request's MCP servers as a per-server tool count, plus the two ways to go
     * deeper: {@code list_mcp_tools} for a server's tool names, {@code search_tool} for one tool's
     * schema. See {@link com.summit.core.mcp.McpToolScope}.
     */
    @Override
    public PromptAssembler withMcpToolPrompt(List<McpResume> mcpTools) {
        if (mcpTools == null || mcpTools.isEmpty()) {
            return this;
        }

        // Counted here rather than passed in, so every caller keeps the flat résumé interface.
        Map<String, Integer> counts = new TreeMap<>();
        for (McpResume tool : mcpTools) {
            counts.merge(tool.server() == null ? "unknown" : tool.server(), 1, Integer::sum);
        }

        stringBuilder.append("\n\n### MCP Prompt\n")
                .append("""
                        Remote tools are hosted by MCP servers; the counts below are how many each one contributed.
                        Their parameter schemas are deliberately left out of this prompt. Call `list_mcp_tools` with
                        a server name to list that server's tools (name and description), or `search_tool` with a
                        keyword to get one tool's schema directly. A tool found either way becomes callable from your
                        next turn on.
                        """);
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            stringBuilder.append("\n- ").append(entry.getKey())
                    .append(" count: ").append(entry.getValue());
        }
        return this;
    }

    @Override
    public PromptAssembler withMemoryPrompt(List<String> memory) {
        stringBuilder.append("\n\n## Memory Prompt\n").append(String.join("\n", memory));
        return this;
    }

    @Override
    public PromptAssembler withSkillPrompt(List<SkillResume> skills) {
        if(skills == null || skills.isEmpty())return this;

        stringBuilder.append("\n\n## Skill Prompt\n");

        for (SkillResume skill : skills) {
            stringBuilder.append("\n- ").append(skill.name()).append(": ").append(normalize(skill.description()));
        }

        return this;
    }

    /**
     * Returns the assembled prompt, without the blank lines the section separators leave at either
     * end. Every section opens with {@code \n\n} so that sections never run together; the first and
     * last ones have nothing to be separated from.
     */
    @Override
    public String complete() {
        this.systemPrompt = this.stringBuilder.toString().strip();
        this.stringBuilder.setLength(0);
        return this.systemPrompt;
    }

    /**
     * Appends the environment variables exposed to the workspace, with secret-looking values redacted.
     */
    private void appendEnvironmentVariables(@Nullable Map<String, String> envs) {
        if (envs == null || envs.isEmpty()) {
            return;
        }
        Map<String, String> sorted = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        envs.forEach((key, value) -> {
            if (key != null && !key.isBlank()) {
                sorted.put(key, value);
            }
        });
        if (sorted.isEmpty()) {
            return;
        }
        stringBuilder.append("\n- Environment variables exposed to this workspace:");
        int printed = 0;
        for (Map.Entry<String, String> entry : sorted.entrySet()) {
            if (printed >= MAX_ENV_ENTRIES) {
                stringBuilder.append("\n  - ... (").append(sorted.size() - printed).append(" more)");
                break;
            }
            stringBuilder.append("\n  - ").append(entry.getKey()).append('=')
                    .append(value(entry.getKey(), entry.getValue()));
            printed++;
        }
    }

    private static String value(String key, @Nullable String value) {
        String upper = key.toUpperCase(Locale.ROOT);
        if (SENSITIVE_KEY_MARKERS.stream().anyMatch(upper::contains)) {
            return "<redacted>";
        }
        String text = value == null ? "" : value;
        return text.length() <= MAX_ENV_VALUE_LENGTH
                ? text : text.substring(0, MAX_ENV_VALUE_LENGTH) + "... (truncated)";
    }

    private static boolean isBlank(@Nullable String text) {
        return text == null || text.isBlank();
    }

    /** Collapses a multi-line description to one line so a résumé entry cannot break the list. */
    private static String normalize(@Nullable String text) {
        return text == null ? "" : text.strip().replaceAll("\\s+", " ");
    }

    private static String text(@Nullable Object value) {
        return value == null ? "unknown" : value.toString();
    }
}
