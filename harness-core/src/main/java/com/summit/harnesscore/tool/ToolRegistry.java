package com.summit.harnesscore.tool;

import dev.langchain4j.agent.tool.ToolSpecification;
import lombok.Data;
import lombok.ToString;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
@ToString
@Data
public class ToolRegistry {
    private final Map<String, ToolSpecification> tools = new ConcurrentHashMap<>();
    private final Map<ToolSpecification, Tool> executors = new ConcurrentHashMap<>();

    public void register(ToolSpecification toolSpec,Tool tool) {
        if (tool != null) {
            tools.put(tool.name(), toolSpec);
            executors.put(toolSpec, tool);
        }
    }

    public void unRegister(String toolName) {
        if (toolName != null && !toolName.isBlank()) {
            tools.remove(toolName);
        }
    }

    public ToolSpecification getToolSpec(String toolName) {
        if (toolName != null && !toolName.isBlank()) {
            return tools.get(toolName);
        }
        return null;
    }

    public Tool getTool(ToolSpecification toolSpecification) {
        return this.executors.get(toolSpecification);
    }

    public Tool getTool(String name) {
        ToolSpecification toolSpecification = this.tools.get(name);
        if (toolSpecification != null) {
            return this.getTool(toolSpecification);
        }
        return null;
    }
}
