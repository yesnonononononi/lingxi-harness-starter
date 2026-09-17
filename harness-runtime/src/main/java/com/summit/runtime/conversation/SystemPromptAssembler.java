package com.summit.runtime.conversation;

import com.summit.core.runtime.workspace.Workspace;
import com.summit.core.tool.LoopBoundary;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * Assembles the framework system prompt from the environment, caller context and runtime policy:
 * <ol>
 *   <li><b>start</b> — the framework default template formatted with the runtime
 *       OS type and working directory (environment description + default rules);</li>
 *   <li><b>middle</b> — the user custom system prompt carried by {@code AgentRequest},
 *       appended verbatim only when present (never re-formatted);</li>
 *   <li><b>end</b> — the current execution boundary ({@link LoopBoundary}) description
 *       so the model knows whether it is in a read-only PLANNING phase or in EXECUTE.</li>
 * </ol>
 *
 * <p>The plan contract is <b>not</b> injected here: it lives in the descriptions / JSON schemas of
 * the plan kernel tools, so there is a single definition of how a plan is written.</p>
 *
 * <p>All optional parts are skipped when absent, which keeps the legacy behaviour
 * (default template only) fully intact.</p>
 */
public class SystemPromptAssembler {

    /**
     * Builds the assembled system prompt text.
     *
     * @param defaultSystemPrompt the framework default template (contains {@code %s} placeholders
     *                            for the OS type and the working directory)
     * @param workspace           the workspace of the current session
     * @param customSystemPrompt  the user custom system prompt (may be {@code null})
     * @param task                the delegated/high-level task (may be {@code null})
     * @param toolList            tools advertised as available to this request (may be {@code null})
     * @param loopBoundary        the execution boundary of the current request (may be {@code null})
     * @return the assembled system prompt text
     */
    public String assemble(String defaultSystemPrompt, Workspace workspace,
                           @Nullable String customSystemPrompt, @Nullable String task,
                           @Nullable List<String> toolList, @Nullable LoopBoundary loopBoundary) {
        String start = String.format(defaultSystemPrompt,
                workspace.runtimeEnvironment().osType(),
                workspace.workDir());
        StringBuilder result = new StringBuilder(start);

        result.append("\n\n## Execution environment")
                .append("\n- Commands run in the assigned workspace environment, not necessarily on the host machine.")
                .append("\n- Reported OS: ").append(workspace.runtimeEnvironment().osType())
                .append("; shell: ").append(workspace.runtimeEnvironment().shellType()).append('.');
        if (workspace.runtimeEnvironment().isolated()) {
            result.append("\n- This workspace is isolated; host tools and files are available only when explicitly exposed.");
        }

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
        if (loopBoundary != null) {
            result.append("\n\n## 当前执行边界 (Current Execution Boundary)")
                    .append("\n边界: ").append(loopBoundary.name())
                    .append(" — ").append(loopBoundary.description);

        }
        return result.toString();
    }
}
