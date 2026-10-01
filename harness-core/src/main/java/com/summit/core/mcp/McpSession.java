package com.summit.core.mcp;

import com.summit.core.tool.ToolDefinition;
import com.summit.core.tool.ToolExecutor;

import java.util.List;

/**
 * One MCP server connected for the lifetime of a request.
 *
 * <p>This is the dependency-inversion seam of the MCP extension: core knows a session only as
 * "a named provider of tools that can be closed", while the concrete implementation lives in the
 * transport adapter and wraps its own client.</p>
 */
public interface McpSession extends AutoCloseable {

    /** Server name as declared by the request configuration. */
    String name();

    /**
     * The description the server published while initializing — the MCP {@code instructions} field —
     * or an empty string when it published none. Defaulted so that a session which carries no
     * description needs no boilerplate.
     */
    default String description() {
        return "";
    }

    /** Tools discovered on this server, already mapped to the harness tool model. */
    List<ToolDefinition<? extends ToolExecutor>> tools();

    @Override
    void close();
}
