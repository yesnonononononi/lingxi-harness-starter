package com.summit.tools.terminal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.summit.harnesscore.runtime.ShellType;
import com.summit.harnesscore.runtime.Workspace;
import com.summit.harnesscore.tool.ToolDefinition;
import com.summit.harnesscore.tool.ToolExecuteResult;
import com.summit.harnesscore.tool.ToolExecution;
import com.summit.harnesscore.tool.ToolExecutor;

import com.summit.tools.arguments.ExecuteCommandRequest;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Getter
public class CommandToolDefinitionExecutor implements ToolExecutor {
    private final ObjectMapper objectMapper;
    private final static int JOIN_TIME = 1;

    public CommandToolDefinitionExecutor(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public @NonNull ToolExecuteResult execute(ToolExecution toolExecution) {
        try {
            // resolve args
            ExecuteCommandRequest request = resolveArgs(toolExecution);
            log.info("【ToolCall】 {}", request.getCommand());


            if (request.getCommand() == null || request.getCommand().isBlank())
                return ToolExecuteResult.err(toolExecution.getId(), toolExecution.getToolDefinition().toolSpecification(), "instruction is empty");

            // ensure the instruction is accessible
            if (!InstructionGuard.process(request.getCommand())) {
                return ToolExecuteResult.err(toolExecution.getId(), toolExecution.getToolDefinition().toolSpecification(), "instruction is not accessible");
            }

            // execute

            return process(request, toolExecution.getWorkspace(), toolExecution, toolExecution.getToolDefinition());

        } catch (Exception e) {
            return ToolExecuteResult.err(toolExecution.getId(), toolExecution.getToolDefinition().toolSpecification(), "tool execute failed : " + e);
        }
    }

    public ToolExecuteResult process(ExecuteCommandRequest request, Workspace workspace, ToolExecution toolExecution, ToolDefinition<?> toolDefinition) throws IOException, InterruptedException {




        StringBuilder stringBuilder = new StringBuilder();
        AtomicBoolean truncated = new AtomicBoolean(false);

        ShellType shellType = workspace.runtimeEnvironment().shellType();

        if (shellType == null) throw new IllegalStateException("Unknown operating system");

        ProcessBuilder processBuilder = new ProcessBuilder(
                shellType.buildCommand(request.getCommand())
        );

        // set working directory
        processBuilder.directory(new File(workspace.workDir()));

        // integrate error stream with output stream
        processBuilder.redirectErrorStream(true);

        // activate process
        Process process = processBuilder.start();

        // get output from virtual thread, buffer up to maxOutput tokens (~3 chars/token) to avoid blowing up the model context
        long maxBufferChars = toolDefinition.maxOutput() * 3L;
        Thread outputThread = Thread.ofVirtual().start(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), workspace.runtimeEnvironment().charset()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (stringBuilder.length() >= maxBufferChars) {
                        truncated.set(true);
                        continue;
                    }
                    stringBuilder.append(line).append("\n");
                }
                if (!truncated.get()) {
                    stringBuilder.append("Exiting code :").append(process.exitValue());
                }
            } catch (IOException e) {
                log.warn("Failed to read process output: {}", request.getCommand(), e);
                stringBuilder.append("\n[OUTPUT_READ_ERROR] failed to read process output: ")
                        .append(e.getMessage())
                        .append("\n");
            }

        });

        // wait for process to complete
        if (!process.waitFor(toolDefinition.timeout(), TimeUnit.SECONDS)) {
            // stop if not completed or timeout
            process.destroy();
            if (process.isAlive()) {
                process.destroyForcibly();
            }

            outputThread.interrupt();


            outputThread.join(Duration.of(JOIN_TIME, ChronoUnit.SECONDS));
            return ToolExecuteResult.err(toolExecution.getId(), toolExecution.getToolDefinition().toolSpecification(), "process timeout");
        }

        // wait for output thread to complete
        outputThread.join();


        String processResult = stringBuilder.toString();
        if (truncated.get()) {
            processResult += String.format("""
                    [OUTPUT_TRUNCATED] 命令输出超过 %s token 的系统预算，已自动截断。
                    请改用更精确的命令；
                    """, toolDefinition.maxOutput()
            );
        }
        return ToolExecuteResult.success(toolExecution.getId(), toolExecution.getToolDefinition().toolSpecification(), processResult);
    }

    private ExecuteCommandRequest resolveArgs(ToolExecution toolExecution) throws JsonProcessingException {
        String args = toolExecution.getArgs();
        return objectMapper.readValue(args, ExecuteCommandRequest.class);
    }


}
