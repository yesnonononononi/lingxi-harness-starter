package com.summit.tools.file.read;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.summit.harnesscore.runtime.Workspace;
import com.summit.harnesscore.tool.ToolExecuteResult;
import com.summit.harnesscore.tool.ToolExecution;
import com.summit.harnesscore.tool.ToolExecutor;

import com.summit.tools.arguments.ReadFileRequest;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Objects;

@Slf4j
public class ReadFileToolExecutor implements ToolExecutor {
    private final ObjectMapper objectMapper;

    public ReadFileToolExecutor(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public @NonNull ToolExecuteResult execute(ToolExecution toolExecution) {
        try {
            ReadFileRequest request = resolveArgs(toolExecution);

            log.info("【ToolCall】 read_file :{}", request.getPath());

            String content = FileLineReader.read(new File(request.getPath()), request.getStartLine(), request.getEndLine());

            return ToolExecuteResult.success(toolExecution.getId(), toolExecution.getToolDefinition().toolSpecification(), content);
        } catch (Exception e) {
            return ToolExecuteResult.err(toolExecution.getId(), toolExecution.getToolDefinition().toolSpecification(), "tool execute failed : " + e);
        }
    }

    private ReadFileRequest resolveArgs(ToolExecution execution) throws JsonProcessingException {
        ReadFileRequest request = objectMapper.readValue(execution.getArgs(), ReadFileRequest.class);
        request.setPath(String.valueOf(execution.getWorkspace().resolve(request.getPath()).normalize()));
        return request;
    }



}
