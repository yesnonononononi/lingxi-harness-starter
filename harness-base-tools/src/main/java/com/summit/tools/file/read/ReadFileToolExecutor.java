package com.summit.tools.file.read;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.summit.harnesscore.runtime.Workspace;
import com.summit.harnesscore.tool.ToolExecuteResult;
import com.summit.harnesscore.tool.ToolExecution;
import com.summit.harnesscore.tool.ToolExecutor;

import com.summit.tools.arguments.ReadFileRequest;
import org.jspecify.annotations.NonNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class ReadFileToolExecutor implements ToolExecutor {
    private final ObjectMapper objectMapper;

    public ReadFileToolExecutor(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public @NonNull ToolExecuteResult execute(ToolExecution toolExecution) {
        try {
            Path filePath = resolveArgs(toolExecution);

            String content =  read(filePath);
            return ToolExecuteResult.success(toolExecution.getId(), toolExecution.getToolSpecification(), content);
        }catch (Exception e){
            return ToolExecuteResult.err(toolExecution.getId(), toolExecution.getToolSpecification(), "tool execute failed : "+e);
        }
    }

    private Path resolveArgs(ToolExecution execution) throws JsonProcessingException {
        ReadFileRequest request = objectMapper.readValue(execution.getArgs(), ReadFileRequest.class);
        String path = request.getPath();
        Workspace workspace = execution.getWorkspace();
        return workspace.resolve(path)
                .normalize();
    }

    private String read(Path path) throws IOException {
        return Files.readString(path);
    }


}
