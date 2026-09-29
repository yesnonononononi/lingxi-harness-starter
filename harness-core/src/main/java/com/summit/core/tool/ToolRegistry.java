package com.summit.core.tool;

import lombok.Data;
import lombok.ToString;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Process-wide registry of the tools the application declares as beans.
 *
 * <p>Deliberately static for the whole container lifetime: request-level tools such as MCP servers
 * live in their own request scope and are never registered here, so this map has no removal path.</p>
 */
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

    public <T extends ToolExecutor>ToolDefinition<T> getTool(String toolName) {
        if (toolName != null && !toolName.isBlank()) {
            return (ToolDefinition<T>) tools.get(toolName);
        }
        return null;
    }

}
