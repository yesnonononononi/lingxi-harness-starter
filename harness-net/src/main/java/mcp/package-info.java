/**
 * MCP client implementation on top of the official {@code io.modelcontextprotocol.sdk} client.
 *
 * <p>The package is the only one that touches SDK types on the framework side: everything it
 * exposes upwards is expressed in core terms ({@link com.summit.core.mcp.McpSession},
 * {@link com.summit.core.mcp.McpToolScope}, {@link com.summit.core.tool.ToolDefinition}).</p>
 *
 * <h2>Layers</h2>
 *
 * <ul>
 *   <li>{@link mcp.McpSessionFactory} — the single entry point: validate, pick a transport, open
 *       and initialize a connection, publish its tools, schedule health checks on a caller-provided
 *       daemon scheduler, and close everything it created.</li>
 *   <li>{@link mcp.McpResilientConnection} — owns one client behind an {@link java.util.concurrent.atomic.AtomicReference},
 *       pings it, and rebuilds it on failure. A rebuild never publishes into a closing connection.</li>
 *   <li>{@link mcp.McpSyncSession} — an initialized, tool-populated session with a lifetime contract.</li>
 *   <li>{@link mcp.transport} — transport construction ({@code streamable-http} and {@code stdio}),
 *       including the Windows command resolution without which {@code npx}-style commands cannot be
 *       started at all, and the sandbox-owned stdio variant that closes its launch recipe first.</li>
 *   <li>{@link mcp.json} — the Jackson 2 adapter of the SDK's {@code McpJsonMapper} (the project is
 *       a Jackson 2 code base, so the aggregate {@code mcp} artifact with its Jackson 3 mapper is
 *       deliberately not used).</li>
 * </ul>
 *
 * <h2>Conventions</h2>
 *
 * <ul>
 *   <li>A published tool is named and identified as {@code prefix + <server tool name>}, while the
 *       call itself always uses the server's own name: {@link mcp.McpToolInvoker} holds it from
 *       assembly time.</li>
 *   <li>Every failure on the tool execution path becomes {@link com.summit.core.tool.ToolExecuteResult#err};
 *       nothing is thrown at the runtime.</li>
 *   <li>An unreachable server only removes its own tools: the caller logs and continues.</li>
 *   <li>Logs never contain header values, environment values or a URL query: only server names,
 *       connection kinds, argv and key sets.</li>
 * </ul>
 */
package mcp;
