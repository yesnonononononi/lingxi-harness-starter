package com.summit.tools.terminal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.summit.core.runtime.ShellType;
import com.summit.core.runtime.Workspace;
import com.summit.core.runtime.WorkspaceBridge;
import com.summit.core.tool.*;
import com.summit.tools.arguments.ExecuteCommandRequest;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;

import java.io.IOException;

/**
 * The pure executor of the command tool: only parses arguments and runs the command.
 *
 * <p>Human approval (PRE_EXEC_CONFIRM / DANGEROUS_BLOCK) has been decoupled from
 * this class into {@link CommandApprovalToolInterceptor} — whether a command needs
 * confirmation and when to suspend or let it through are all decided by the generic
 * tool interceptor chain before this executor is invoked.</p>
 */
@Slf4j
@Getter
@RequiredArgsConstructor
public class CommandToolDefinitionExecutor implements ToolExecutor {
    private final ObjectMapper objectMapper;


    @Override
    public @NonNull ToolExecuteResult execute(ToolExecution toolExecution) {
        try {
            // resolve args
            ExecuteCommandRequest request = resolveArgs(toolExecution);
            log.info("【ToolCall】 {}", request.getCommand());

            if (request.getCommand() == null || request.getCommand().isBlank())
                return ToolExecuteResult.err(toolExecution.getId(), toolExecution.getToolDefinition(), "instruction is empty");

            // execute
            return process(request, toolExecution.getWorkspace(), toolExecution, toolExecution.getToolDefinition());

        } catch (Exception e) {
            return ToolExecuteResult.err(toolExecution.getId(), toolExecution.getToolDefinition(), "tool execute failed : " + e);
        }
    }

    /**
     * execute tools
     */
    public ToolExecuteResult process(ExecuteCommandRequest request, Workspace workspace, ToolExecution toolExecution, ToolDefinition<?> toolDefinition) throws IOException, InterruptedException {

        ShellType shellType = workspace.runtimeEnvironment().shellType();

        if (shellType == null) throw new IllegalStateException("Unknown operating system");

        // The shell wrapper depends on the target environment; the bridge decides
        // where the command actually runs (host process vs. sandbox).
        WorkspaceBridge.CommandResult result = workspace.bridge().execute(
                shellType.buildCommand(request.getCommand()),
                workspace.workDir(),
                workspace.runtimeEnvironment().charset(),
                toolDefinition.timeout(),
                toolDefinition.maxOutput() * 3L
        );

        if (result.timedOut()) {
            return ToolExecuteResult.err(toolExecution.getId(), toolExecution.getToolDefinition(), "process timeout");
        }

        String processResult = result.truncated()
                ? result.output()
                : result.output() + "Exiting code :" + result.exitCode();
        if (result.truncated()) {
            processResult += String.format("""
                    [OUTPUT_TRUNCATED] 命令输出超过 %s token 的系统预算，已自动截断。
                    请改用更精确的命令；
                    """, toolDefinition.maxOutput()
            );
        }
        return ToolExecuteResult.success(toolExecution.getId(), toolExecution.getToolDefinition(), processResult);
    }

    /**
     * resolve the tool args of agent
     * str -> toolExecution
     * @param toolExecution execution
     */
    private ExecuteCommandRequest resolveArgs(ToolExecution toolExecution) throws JsonProcessingException {
        String args = toolExecution.getArgs();
        return objectMapper.readValue(args, ExecuteCommandRequest.class);
    }

}
