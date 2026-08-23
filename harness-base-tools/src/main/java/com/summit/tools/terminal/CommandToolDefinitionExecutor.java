package com.summit.tools.terminal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.summit.harnesscore.runtime.Workspace;
import com.summit.harnesscore.tool.ToolExecuteResult;
import com.summit.harnesscore.tool.ToolExecution;
import com.summit.harnesscore.tool.ToolExecutor;

import com.summit.tools.arguments.ExecuteCommandRequest;
import lombok.Getter;
import org.jspecify.annotations.NonNull;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;

@Getter
public class CommandToolDefinitionExecutor implements ToolExecutor {
    private final ObjectMapper objectMapper;
    private final TerminalConfig terminalConfig;

    public CommandToolDefinitionExecutor(ObjectMapper objectMapper,TerminalConfig terminalConfig) {
        this.objectMapper = objectMapper;
        this.terminalConfig = terminalConfig;
    }

    @Override
    public @NonNull ToolExecuteResult execute(ToolExecution toolExecution) {
        try {
            // resolve args
            ExecuteCommandRequest request = resolveArgs(toolExecution);
            if (request.getInstruction() == null || request.getInstruction().isBlank())
                return ToolExecuteResult.err(toolExecution.getId(), toolExecution.getToolSpecification(), "instruction is empty");

            // ensure the instruction is accessible
            if (!InstructionGuard.process(request.getInstruction())) {
                return ToolExecuteResult.err(toolExecution.getId(), toolExecution.getToolSpecification(), "instruction is not accessible");
            }

            // execute

            return process(request, toolExecution.getWorkspace(), toolExecution);

        } catch (Exception e) {
            return ToolExecuteResult.err(toolExecution.getId(), toolExecution.getToolSpecification(), "tool execute failed : " + e);
        }
    }

    public ToolExecuteResult process(ExecuteCommandRequest request, Workspace workspace, ToolExecution toolExecution) throws IOException, InterruptedException {


        StringBuilder stringBuilder = new StringBuilder();

        ProcessBuilder processBuilder = new ProcessBuilder(
               ShellAdapter.shell(request.getInstruction(), workspace.getOsType())
        );

        // set working directory
        processBuilder.directory(new File(workspace.getWorkDir()));

        // integrate error stream with output stream
        processBuilder.redirectErrorStream(true);

        // activate process
        Process process = processBuilder.start();

        // get output from virtual thread
        Thread outputThread = Thread.ofVirtual().start(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), workspace.getCharset()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    stringBuilder.append(line).append("\n");
                }
                stringBuilder.append("Exiting code :").append(process.exitValue());
            } catch (IOException ignore) {
            }

        });

        // wait for process to complete
        if(!process.waitFor(this.terminalConfig.getTimeout(), TimeUnit.SECONDS)){
            // stop if not completed or timeout
            process.destroy();
            if (process.isAlive()){
                process.destroyForcibly();
            }
            outputThread.interrupt();
            return ToolExecuteResult.err(toolExecution.getId(), toolExecution.getToolSpecification(), "process timeout");
        }

        // wait for output thread to complete
        outputThread.join();


        String processResult = stringBuilder.toString();
        return ToolExecuteResult.success(toolExecution.getId(), toolExecution.getToolSpecification(), processResult);
    }

    private ExecuteCommandRequest resolveArgs(ToolExecution toolExecution) throws JsonProcessingException {
        String args = toolExecution.getArgs();
        return objectMapper.readValue(args, ExecuteCommandRequest.class);
    }



}
