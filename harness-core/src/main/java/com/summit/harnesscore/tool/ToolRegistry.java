package com.summit.harnesscore.tool;

import lombok.Data;
import lombok.ToString;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
@ToString
@Data
@SuppressWarnings("unchecked")
public class ToolRegistry {
    private final Map<String, ToolDefinition<? extends ToolExecutor>> tools = new ConcurrentHashMap<>();


    public <T extends ToolExecutor>void register(String name, ToolDefinition< ? extends T> tool) {
        if (tool != null) {
            tools.put(name, tool);
        }
    }

    public void unRegister(String toolName) {
        if (toolName != null && !toolName.isBlank()) {
            tools.remove(toolName);
        }
    }

    public <T extends ToolExecutor>ToolDefinition<T> getTool(String toolName) {
        if (toolName != null && !toolName.isBlank()) {
            return (ToolDefinition<T>) tools.get(toolName);
        }
        return null;
    }

}
