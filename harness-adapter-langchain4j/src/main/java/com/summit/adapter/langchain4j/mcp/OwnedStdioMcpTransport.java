package com.summit.adapter.langchain4j.mcp;

import com.summit.core.runtime.workspace.WorkspaceStdioLaunch;
import dev.langchain4j.mcp.client.transport.McpOperationHandler;
import dev.langchain4j.mcp.client.transport.stdio.StdioMcpTransport;

import java.io.IOException;

/** Keeps protocol handling in LangChain4j and closes the sandbox before its supervising pipe. */
final class OwnedStdioMcpTransport extends StdioMcpTransport {
    private final WorkspaceStdioLaunch launch;
    private boolean closed;
    OwnedStdioMcpTransport(WorkspaceStdioLaunch launch) {
        super(StdioMcpTransport.builder().command(launch.command()).environment(launch.environment()).logEvents(false));
        this.launch = launch;
    }
    @Override public synchronized void start(McpOperationHandler handler) {
        if (closed) throw new IllegalStateException("MCP stdio session is closed");
        super.start(handler);
    }
    @Override public synchronized void close() throws IOException {
        if (closed) return;
        closed = true;
        try { launch.close(); }
        finally { super.close(); }
    }
}
