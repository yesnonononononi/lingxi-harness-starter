package com.summit.runtime.tool;

import com.summit.core.tool.ToolDefinition;

import java.util.Collection;
import java.util.List;

/**
 * Selects the model-facing declarations from the static registry.
 *
 * <p>The result depends on the registry and the request whitelist only — never on the request's MCP
 * scope or on what the model has looked up — so it stays identical for every round of a run and the
 * provider's prefix cache survives tool discovery. Remote tools are reached through the
 * {@code call_mcp_tool} gateway, which is an ordinary registered definition. Framework kernel tools
 * are exempt from the whitelist (see {@link com.summit.core.tool.KernelTools}): they are declared
 * here by default and a caller never has to name them.</p>
 */
public final class ModelToolVisibility {
    private ModelToolVisibility() {}

    public static List<ToolDefinition<?>> select(Collection<? extends ToolDefinition<?>> registered,
                                                 List<String> allowed) {
        return registered.stream()
                .filter(tool -> tool.allowedFor(allowed))
                .<ToolDefinition<?>>map(tool -> tool)
                .toList();
    }
}
