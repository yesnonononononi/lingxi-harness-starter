package com.summit.runtime.conversation;

import com.summit.core.runtime.RuntimeEnvironment;
import com.summit.core.runtime.workspace.Workspace;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/** Assembles the framework system prompt from the environment, caller context and the tool set of the run: */
public class SystemPromptAssembler {

    /** Environment variables listed verbatim before the rest is collapsed into a count. */
    private static final int MAX_ENV_ENTRIES = 40;
    /** Value length after which an environment variable value is truncated. */
    private static final int MAX_ENV_VALUE_LENGTH = 200;
    /** Key fragments whose value never reaches the model. */
    private static final List<String> SENSITIVE_KEY_MARKERS =
            List.of("KEY", "TOKEN", "SECRET", "PASSWORD", "PASSWD", "CREDENTIAL", "PRIVATE");

    /**
     * Builds the assembled system prompt text.
     *
     * @param defaultSystemPrompt the framework default template (may contain {@code %s} placeholders
     *                            for the OS type and the working directory)
     * @param workspace           the workspace of the current session (may be {@code null})
     * @param customSystemPrompt  the application system prompt (may be {@code null})
     * @param task                the delegated/high-level task (may be {@code null})
     * @param toolList            tools advertised as available to this request (may be {@code null})
     * @return the assembled system prompt text
     */
    public String assemble(String defaultSystemPrompt, @Nullable Workspace workspace,
                           @Nullable String customSystemPrompt, @Nullable String task,
                           @Nullable List<String> toolList) {
        RuntimeEnvironment environment = workspace == null ? null : workspace.runtimeEnvironment();
        StringBuilder result = new StringBuilder(formatDefault(defaultSystemPrompt, workspace, environment));
        appendEnvironment(result, workspace, environment);

        if (customSystemPrompt != null && !customSystemPrompt.isBlank()) {
            result.append("\n\n").append(customSystemPrompt);
        }
        if (task != null && !task.isBlank()) {
            result.append("\n\n## 当前任务 (Current Task)\n").append(task.trim());
        }
        if (toolList != null && !toolList.isEmpty()) {
            result.append("\n\n## 可用的应用工具 (Available Application Tools)");
            toolList.stream().filter(name -> name != null && !name.isBlank())
                    .map(String::trim).forEach(name -> result.append("\n- ").append(name));
        }
        return result.toString();
    }

    /** Fills the OS type / working directory placeholders of the default template; an application template without placeholders is kept verbatim. */
    private static String formatDefault(String defaultSystemPrompt, @Nullable Workspace workspace,
                                        @Nullable RuntimeEnvironment environment) {
        if (defaultSystemPrompt == null || !defaultSystemPrompt.contains("%s")) {
            return defaultSystemPrompt == null ? "" : defaultSystemPrompt;
        }
        return String.format(defaultSystemPrompt,
                environment == null ? null : environment.osType(),
                workspace == null ? null : workspace.workDir());
    }

    /** Appends the factual description of the environment the commands of this run execute in. */
    private static void appendEnvironment(StringBuilder out, @Nullable Workspace workspace,
                                          @Nullable RuntimeEnvironment environment) {
        if (environment == null) {
            return;
        }
        out.append("\n\n## Execution environment")
                .append("\n- Commands run in the assigned workspace environment, not necessarily on the host machine.")
                .append("\n- Reported OS: ").append(text(environment.osType()))
                .append("; shell: ").append(text(environment.shellType()))
                .append("; charset: ").append(text(environment.charset())).append('.');
        if (workspace != null) {
            out.append("\n- Working directory: ").append(workspace.workDir());
        }
        if (environment.isolated()) {
            out.append("\n- This workspace is isolated; host tools and files are available only when explicitly exposed.");
        } else {
            out.append("\n- This workspace is not isolated; host tools and files are reachable.");
        }
        appendEnvironmentVariables(out, environment.envs());
    }

    /** Appends the environment variables exposed to the workspace, with secret-looking values redacted. */
    private static void appendEnvironmentVariables(StringBuilder out, @Nullable Map<String, String> envs) {
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
        out.append("\n- Environment variables exposed to this workspace:");
        int printed = 0;
        for (Map.Entry<String, String> entry : sorted.entrySet()) {
            if (printed >= MAX_ENV_ENTRIES) {
                out.append("\n  - ... (").append(sorted.size() - printed).append(" more)");
                break;
            }
            out.append("\n  - ").append(entry.getKey()).append('=')
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

    private static String text(@Nullable Object value) {
        return value == null ? "unknown" : value.toString();
    }
}
