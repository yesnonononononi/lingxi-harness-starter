package com.summit.runtime.prompt;

import com.summit.core.conversation.message.SystemMessageEntity;
import com.summit.core.prompt.EnvironmentFormatter;
import com.summit.core.prompt.PromptAssembler;
import com.summit.core.runtime.RuntimeEnvironment;
import com.summit.core.runtime.workspace.Workspace;
import lombok.Getter;
import org.jspecify.annotations.Nullable;

import java.util.Map;

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
        String formatted = EnvironmentFormatter.formatEntries(envs, "  ");
        if (formatted != null) {
            stringBuilder.append("\n- Environment variables exposed to this workspace:\n").append(formatted);
        }
    }

    private static boolean isBlank(@Nullable String text) {
        return text == null || text.isBlank();
    }

    private static String text(@Nullable Object value) {
        return value == null ? "unknown" : value.toString();
    }
}
