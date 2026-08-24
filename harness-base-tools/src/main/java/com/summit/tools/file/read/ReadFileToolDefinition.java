package com.summit.tools.file.read;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.summit.harnesscore.tool.Tool;
import com.summit.harnesscore.tool.ToolExecutor;

public class ReadFileToolDefinition implements Tool {
    private final ObjectMapper objectMapper;

    public ReadFileToolDefinition(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }
    @Override
    public String name() {
        return "read_file";
    }

    @Override
    public String id() {
        return String.valueOf("read_file".hashCode());
    }


    @Override
    public ToolExecutor executor() {
        return new ReadFileToolExecutor(objectMapper);
    }
}
