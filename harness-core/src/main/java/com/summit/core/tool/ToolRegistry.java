package com.summit.core.tool;

import lombok.Data;
import lombok.ToString;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

@ToString
@Data
@SuppressWarnings("unchecked")
public class ToolRegistry {
    private final List<ToolDefinition<? extends ToolExecutor>> toolDefinitionList;
    private final Map<String, ToolDefinition<? extends ToolExecutor>> tools;

    public ToolRegistry(List<ToolDefinition<? extends ToolExecutor>> toolDefinitionList) {
        this.toolDefinitionList = toolDefinitionList;
        this.tools = new ConcurrentHashMap<>(this.toolDefinitionList.stream().collect(Collectors.toMap(ToolDefinition::name,t-> t)));
    }

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
