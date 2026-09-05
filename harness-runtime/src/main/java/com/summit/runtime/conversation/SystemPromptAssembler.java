package com.summit.runtime.conversation;

import com.summit.core.runtime.Workspace;
import com.summit.core.tool.LoopBoundary;
import org.jspecify.annotations.Nullable;

/**
 * Assembles the framework system prompt in three parts:
 * <ol>
 *   <li><b>start</b> — the framework default template formatted with the runtime
 *       OS type and working directory (environment description + default rules);</li>
 *   <li><b>middle</b> — the user custom system prompt carried by {@code AgentRequest},
 *       appended verbatim only when present (never re-formatted);</li>
 *   <li><b>end</b> — the current execution boundary ({@link LoopBoundary}) description
 *       so the model knows whether it is in a read-only PLANNING phase or in EXECUTE.</li>
 * </ol>
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
     * @param loopBoundary        the execution boundary of the current request (may be {@code null})
     * @return the assembled three-part system prompt text
     */
    public String assemble(String defaultSystemPrompt, Workspace workspace,
                           @Nullable String customSystemPrompt, @Nullable LoopBoundary loopBoundary) {
        String start = String.format(defaultSystemPrompt,
                workspace.runtimeEnvironment().osType(),
                workspace.workDir());
        StringBuilder result = new StringBuilder(start);

        if (customSystemPrompt != null && !customSystemPrompt.isBlank()) {
            result.append("\n\n").append(customSystemPrompt);
        }
        if (loopBoundary != null) {
            result.append("\n\n## 当前执行边界 (Current Execution Boundary)")
                    .append("\n边界: ").append(loopBoundary.name())
                    .append(" — ").append(loopBoundary.description);
        }
        return result.toString();
    }
}
