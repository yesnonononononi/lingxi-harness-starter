package com.summit.harness.springbootautoconfigure.config.mcp;

import com.summit.core.conf.McpConfig;
import com.summit.core.mcp.McpProvider;
import com.summit.core.mcp.McpRegister;
import com.summit.core.mcp.McpToolScope;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Opens the request's MCP scope through the configured provider.
 *
 * <p>Deliberately stateless: MCP tools never enter the process-wide registry, so repeated requests
 * carrying the same configuration cannot collide and every scope is discarded with its request.</p>
 */
@Slf4j
@RequiredArgsConstructor
public class McpToolRegistrar implements McpRegister {
    private final McpProvider provider;

    @Override
    public McpToolScope open(@NonNull McpConfig mcpConfig) {
        McpToolScope scope = provider.openScope(mcpConfig);
        log.info("Opened {} MCP tool(s) for this request", scope.getTools().size());
        return scope;
    }
}
