package com.summit.runtime.tools.file.definition;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.summit.harnesscore.tool.Tool;
import com.summit.harnesscore.tool.ToolExecutor;
import com.summit.runtime.tools.ReadFileToolExecutor;

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
    public String description() {
        return "read file";
    }

    @Override
    public ToolExecutor executor() {
        return new ReadFileToolExecutor(objectMapper);
    }
}
