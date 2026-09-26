package com.summit.harness.springbootautoconfigure.conf.mcp;

import com.summit.core.mcp.McpProvider;
import com.summit.core.tool.ToolDefinition;
import com.summit.core.tool.ToolRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.SmartInitializingSingleton;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Discovers once, after all singleton tools have been assembled and before application readiness. */
@Slf4j
public class McpToolRegistrar implements SmartInitializingSingleton {
    private final ToolRegistry registry;
    private final List<McpProvider> providers;

    public McpToolRegistrar(ToolRegistry registry, List<McpProvider> providers) {
        this.registry = registry;
        this.providers = providers;
    }

    @Override
    public void afterSingletonsInstantiated() {
        Map<String, ToolDefinition<?>> discovered = new LinkedHashMap<>();
        for (McpProvider provider : providers) {
            List<ToolDefinition<?>> tools;
            try {
                tools = provider.provide();
            } catch (Exception failure) {
                log.warn("MCP provider {} discovery failed ({})", provider.getClass().getSimpleName(),
                        failure.getClass().getSimpleName());
                continue;
            }
            for (ToolDefinition<?> tool : tools) {
                if (registry.getTool(tool.name()) != null || discovered.putIfAbsent(tool.name(), tool) != null) {
                    throw new IllegalStateException("Duplicate MCP tool name: " + tool.name()
                            + "; configure a unique tool-name-prefix");
                }
            }
        }
        discovered.forEach((name, tool) -> {
            if (registry.getTools().putIfAbsent(name, tool) != null) {
                throw new IllegalStateException("Tool was concurrently registered: " + name);
            }
        });
        log.info("Registered {} MCP tool(s)", discovered.size());
    }
}
