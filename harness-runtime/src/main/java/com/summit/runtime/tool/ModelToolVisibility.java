package com.summit.runtime.tool;

import com.summit.core.tool.ToolDefinition;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Selects model-facing declarations from the registry and one MCP disclosure snapshot.
 * Execution admission is separate and can allow calls to MCP tools that have not been disclosed.
 */
public final class ModelToolVisibility {
    private ModelToolVisibility() {}

    public static Selection select(Collection<? extends ToolDefinition<?>> registered,
                                   List<ToolDefinition<?>> disclosed, List<String> allowed) {
        Map<String, ToolDefinition<?>> mcpByName = new LinkedHashMap<>();
        for (ToolDefinition<?> tool : disclosed) {
            mcpByName.putIfAbsent(tool.name(), tool);
        }

        List<ToolDefinition<?>> visibleRegistered = new ArrayList<>();
        for (ToolDefinition<?> tool : registered) {
            // Disclosure admits the name, but a registered definition owns any collision.
            if (mcpByName.remove(tool.name()) != null || tool.allowedFor(allowed)) {
                visibleRegistered.add(tool);
            }
        }
        return new Selection(List.copyOf(visibleRegistered), List.copyOf(mcpByName.values()));
    }

    public record Selection(List<ToolDefinition<?>> registered, List<ToolDefinition<?>> mcp) {
        public List<ToolDefinition<?>> all() {
            List<ToolDefinition<?>> tools = new ArrayList<>(registered.size() + mcp.size());
            tools.addAll(registered);
            tools.addAll(mcp);
            return List.copyOf(tools);
        }
    }
}
