package com.summit.runtime.tool;

import com.summit.core.mcp.McpToolGateway;
import com.summit.core.tool.ToolDefinition;

import java.util.Collection;
import java.util.List;

/**
 * Selects the model-facing declarations from the static registry.
 *
 * <p>The result depends on the registry and the request whitelist only — never on the request's MCP
 * scope or on what the model has looked up — so it stays identical for every round of a run and the
 * provider's prefix cache survives tool discovery. Remote tools are reached through
 * {@link McpToolGateway}, which is declared wherever the whitelist exposes it.</p>
 */
public final class ModelToolVisibility {
    private ModelToolVisibility() {}

    public static List<ToolDefinition<?>> select(Collection<? extends ToolDefinition<?>> registered,
                                                 List<String> allowed) {
        boolean gatewayExposed = McpToolGateway.admittedBy(allowed);
        return registered.stream()
                .filter(tool -> tool.allowedFor(allowed)
                        || (gatewayExposed && McpToolGateway.isGateway(tool.name())))
                .<ToolDefinition<?>>map(tool -> tool)
                .toList();
    }
}
