package com.summit.tools.terminal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.summit.harnesscore.tool.Tool;
import com.summit.harnesscore.tool.ToolExecutor;

public class ExecuteCommandToolDefinition implements Tool {
    private final ObjectMapper objectMapper;
    private final TerminalConfig terminalConfig;
    public ExecuteCommandToolDefinition(ObjectMapper objectMapper, TerminalConfig terminalConfig) {
        this.objectMapper = objectMapper;
        this.terminalConfig = terminalConfig;
    }

    @Override
    public String name() {
        return "execute_command";
    }

    @Override
    public String id() {
        return String.valueOf("execute-command-tool".hashCode());
    }

    @Override
    public String description() {
        return "execute terminal command";
    }

    @Override
    public ToolExecutor executor() {
        return new CommandToolDefinitionExecutor(this.objectMapper, this.terminalConfig);
    }
}
