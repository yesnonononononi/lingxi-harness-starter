package com.summit.core.tool;

import lombok.ToString;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Process-wide tools, identified by ID and addressed by their unique model-facing names.
 * Request-scoped MCP tools remain outside this registry.
 */
@ToString
public class ToolRegistry {
    private final List<ToolDefinition<? extends ToolExecutor>> toolDefinitionList = new ArrayList<>();
    private final Map<String, ToolDefinition<? extends ToolExecutor>> tools = new LinkedHashMap<>();

    public ToolRegistry(List<ToolDefinition<? extends ToolExecutor>> toolDefinitions) {
        Objects.requireNonNull(toolDefinitions, "toolDefinitions cannot be null");
        for (ToolDefinition<?> definition : toolDefinitions) {
            registerDefinition(definition);
        }
    }

    public synchronized void register(Collection<? extends ToolDefinition<?>> toolDefinitions) {
        Objects.requireNonNull(toolDefinitions, "toolDefinitions cannot be null");
        for (ToolDefinition<?> definition : toolDefinitions) {
            register(definition);
        }
    }

    public synchronized void register(ToolDefinition<?> toolDefinition) {
        registerDefinition(toolDefinition);
    }

    public synchronized void unRegister(String name) {
        ToolDefinition<?> removed = tools.remove(name);
        if (removed != null) {
            toolDefinitionList.remove(removed);
        }
    }

    @SuppressWarnings("unchecked")
    public synchronized <T extends ToolExecutor> ToolDefinition<T> getTool(String toolName) {
        return toolName == null || toolName.isBlank() ? null : (ToolDefinition<T>) tools.get(toolName);
    }

    public synchronized ToolDefinition<?> getToolById(Serializable id) {
        return resolveToolById(id);
    }

    public synchronized List<ToolDefinition<? extends ToolExecutor>> getToolDefinitionList() {
        return List.copyOf(toolDefinitionList);
    }

    public synchronized Map<String, ToolDefinition<? extends ToolExecutor>> getTools() {
        return Map.copyOf(tools);
    }

    private ToolDefinition<?> resolveToolById(Serializable id) {
        for (ToolDefinition<?> definition : toolDefinitionList) {
            if (definition.id().equals(id)) {
                return definition;
            }
        }
        return null;
    }

    private void registerDefinition(ToolDefinition<?> definition) {
        Objects.requireNonNull(definition, "toolDefinition cannot be null");
        // Explicit definition beans are registered first and retain authority over annotations.
        if (resolveToolById(definition.id()) != null) {
            return;
        }
        ToolDefinition<?> existing = tools.get(definition.name());
        if (existing != null) {
            throw new IllegalArgumentException("Tool name '" + definition.name()
                    + "' is already registered with ID '" + existing.id()
                    + "'; cannot register ID '" + definition.id() + "'");
        }
        tools.put(definition.name(), definition);
        toolDefinitionList.add(definition);
    }
}
