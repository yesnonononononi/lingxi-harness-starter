package com.summit.tools.file.edit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.summit.harnesscore.tool.Tool;
import com.summit.harnesscore.tool.ToolExecutor;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class EditFileToolDefinition implements Tool {
    private final ObjectMapper objectMapper;
    private final EditFileToolConfig editFileToolConfig;
    @Override
    public String name() {
        return "edit_file";
    }

    @Override
    public String id() {
        return String.valueOf("edit_file".hashCode());
    }

    @Override
    public String description() {
        return "edit file content";
    }

    @Override
    public ToolExecutor executor() {
        return new EditFileToolExecutor(this.objectMapper, this.editFileToolConfig);
    }
}
