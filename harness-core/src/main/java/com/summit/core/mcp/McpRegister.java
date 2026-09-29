package com.summit.core.mcp;

import com.summit.core.conf.McpConfig;
import lombok.NonNull;

/**
 * Opens the MCP tool scope of one request.
 *
 * <p>Kept separate from {@link McpProvider} so an application can substitute the lifecycle policy
 * (caching, pooling, auditing) without touching the discovery implementation.</p>
 */
public interface McpRegister {

    /** Opens the scope for this request; never {@code null}, empty when no server is declared. */
    McpToolScope open(@NonNull McpConfig mcpConfig);
}
