package com.summit.core.tool;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;

/**
 * Selects the model-facing tool declarations for one request — the single owner of that decision.
 *
 * <p>Everything that reads "which static tools does this run see" goes through here (the model
 * request itself, the context-budget reporter, the token auditor), so a whitelist that admits no
 * application tool can never be reported as "no tools at all" while the model is still being served
 * the framework kernel tools.</p>
 *
 * <p>The result depends on the registry and the request whitelist only — never on the request's MCP
 * scope or on what the model has looked up — and is sorted by name, so it stays identical for every
 * round of a run and the provider's prefix cache survives tool discovery. Remote tools are reached
 * through the {@code call_mcp_tool} gateway, which is an ordinary registered definition. Framework
 * kernel tools are exempt from the whitelist (see {@link KernelTools}): they are declared here by
 * default and a caller never has to name them.</p>
 */
public final class ToolVisibility {
    private ToolVisibility() {
    }

    public static List<ToolDefinition<?>> select(Collection<? extends ToolDefinition<?>> registered,
                                                 List<String> allowed) {
        if (registered == null || registered.isEmpty()) {
            return List.of();
        }
        return registered.stream()
                .filter(tool -> tool.visibleToModel(allowed))
                .sorted(Comparator.comparing(ToolDefinition::name))
                .<ToolDefinition<?>>map(tool -> tool)
                .toList();
    }
}
