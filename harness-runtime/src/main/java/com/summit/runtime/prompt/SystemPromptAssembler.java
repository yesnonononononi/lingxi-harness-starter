package com.summit.runtime.prompt;

import com.summit.core.conversation.message.SystemMessageEntity;
import com.summit.core.prompt.PromptAssembler;
import com.summit.core.runtime.RuntimeEnvironment;
import com.summit.core.runtime.workspace.Workspace;
import lombok.Getter;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Assembles the stable leading system message of one execution: where the agent runs, and the
 * prompt the application wrote.
 *
 * <p>Nothing that changes between rounds belongs here. Remote-tool résumés, skills, memory and the
 * task manifest are rendered by {@code ContextDynamicAssembler} and travel as a user message, so
 * this text stays constant and the provider can serve the prompt-cache prefix it already holds.</p>
 *
 * <p>Sections are appended in the order the caller chains them, and a section whose input is empty
 * is skipped outright: a heading with nothing under it costs tokens and tells the model nothing.</p>
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

    /**
     * Returns the assembled prompt, without the blank lines the section separators leave at either
     * end. Every section opens with {@code \n\n} so that sections never run together; the first and
     * last ones have nothing to be separated from.
     */
    @Override
    public SystemMessageEntity complete() {
        this.systemPrompt = this.stringBuilder.toString().strip();
        this.stringBuilder.setLength(0);

        return SystemMessageEntity.builder()
                .text(this.systemPrompt)
                .build();
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

    private static String text(@Nullable Object value) {
        return value == null ? "unknown" : value.toString();
    }
}
