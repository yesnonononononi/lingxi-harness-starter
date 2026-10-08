package com.summit.runtime.tool;

import com.summit.core.conversation.api.ToolCallRequest;
import com.summit.core.conversation.event.RuntimeEventPublisher;
import com.summit.core.mcp.McpSession;
import com.summit.core.mcp.McpToolScope;
import com.summit.core.runtime.RuntimeEnvironment;
import com.summit.core.runtime.workspace.Workspace;
import com.summit.core.tool.ConcurrentPolicy;
import com.summit.core.tool.ToolDefinition;
import com.summit.core.tool.ToolExecuteCommand;
import com.summit.core.tool.ToolExecuteResult;
import com.summit.core.tool.ToolExecutionContext;
import com.summit.core.tool.ToolRegistry;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Admission rules for request-level MCP tools.
 *
 * <p>A whitelist is written before any MCP server is contacted, so it can never name a discovered
 * tool. These tests pin the compensation: a tool belonging to the request's own scope is admitted
 * even when the whitelist cannot mention it, while everything else keeps the whitelist as its only
 * gate.</p>
 */
class McpToolAdmissionTest {

    private static final String STATIC_TOOL = "read_file";
    private static final String MCP_TOOL = "mcp_github_get_me";

    @Test
    void mcpToolOfThisRequestIsAdmittedDespiteAbsentFromWhitelist() {
        AtomicInteger calls = new AtomicInteger();
        // Whitelist deliberately names only the static tool; the MCP tool is reachable through the
        // request's scope alone.
        try (DefaultToolExecutionManager manager = manager()) {
            ToolExecuteResult result = executeMCP(manager, List.of(STATIC_TOOL), scopeWith(MCP_TOOL, calls));

            assertEquals("me", result.getToolOutput());
            assertEquals(1, calls.get(), "the MCP executor must have run");
        }
    }

    @Test
    void mcpToolIsRejectedWhenNoScopeClaimsIt() {
        AtomicInteger calls = new AtomicInteger();
        try (DefaultToolExecutionManager manager = manager()) {
            // The scope of the request holds a different tool, so the whitelist stays the only gate
            // and does not admit this one.
            ToolExecuteResult result = executeMCP(manager, List.of(STATIC_TOOL), scopeWith("mcp_other_ping", calls));

            assertEquals("Tool not found", result.getToolOutput());
            assertEquals(0, calls.get());
        }
    }

    @Test
    void staticToolIsRejectedWhenWhitelistOmitsIt() {
        AtomicInteger calls = new AtomicInteger();
        try (DefaultToolExecutionManager manager = manager()) {
            // A static tool has exactly one way in: being named. Omitting it is enough to block it.
            List<ToolExecuteResult> results = manager.execute(new ToolExecuteCommand(
                    List.of(new ToolCallRequest("call-1", STATIC_TOOL, "{}")),
                    "execution-1",
                    workspace(),
                    null,
                    null,
                    List.of("something_else"),
                    UUID.randomUUID(),
                    false,
                    McpToolScope.EMPTY
                    )
            );

            assertEquals(1, results.size());
            assertEquals("Tool '" + STATIC_TOOL + "' is not allowed for this agent request",
                    trimReason(results.getFirst().getToolOutput()));
            assertEquals(0, calls.get());
        }
    }

    @Test
    void absentWhitelistAdmitsOnlyTheRequestsOwnMcpTool() {
        AtomicInteger calls = new AtomicInteger();
        try (DefaultToolExecutionManager manager = manager()) {
            // Without a list, nothing is unrestricted: the request's own MCP tool still gets through
            // because a discovered name could never have been written into the list, but the static
            // tool does not.
            ToolExecuteResult result = executeMCP(manager, null, scopeWith(MCP_TOOL, calls));

            assertEquals("me", result.getToolOutput());
            assertEquals(1, calls.get());
        }
    }

    @Test
    void absentWhitelistStillRejectsStaticTools() {
        try (DefaultToolExecutionManager manager = manager()) {
            List<ToolExecuteResult> results = manager.execute(new ToolExecuteCommand(
                    List.of(new ToolCallRequest("call-1", STATIC_TOOL, "{}")),
                    "execution-1", workspace(), null,null, null, UUID.randomUUID(), false, McpToolScope.EMPTY));

            assertEquals(1, results.size());
            assertEquals("Tool '" + STATIC_TOOL + "' is not allowed for this agent request",
                    trimReason(results.getFirst().getToolOutput()));
        }
    }

    @Test
    void whitelistAdmitsAClaimedMcpToolEvenWhenScopeIsEmpty() {
        AtomicInteger calls = new AtomicInteger();
        ToolDefinition<?> definition = mcpDefinition(MCP_TOOL, "me", calls);

        try (DefaultToolExecutionManager manager = new DefaultToolExecutionManager(
                ToolExecutionContext.builder()
                        .toolRegistry(new ToolRegistry(List.of(definition)))
                        .runtimeEventPublisher(new RuntimeEventPublisher(List.of()))
                        .build(),
                invocation -> invocation.getMethod().invoke(invocation.getTarget(), invocation.getContext()),
                List.of())) {
            ToolExecuteResult result = executeMCP(manager, List.of(MCP_TOOL), McpToolScope.EMPTY);

            assertEquals("me", result.getToolOutput());
            assertEquals(1, calls.get());
        }
    }

    // --- helpers ---------------------------------------------------------------------------

    /**
     * Runs one call against the given scope and returns the single result. The MCP tool reaches
     * execution only through {@link ToolExecuteCommand#mcpToolScope()}, which is the production path
     * — a request-level tool is never in the process-wide registry.
     */
    private ToolExecuteResult executeMCP(DefaultToolExecutionManager manager,
                                         List<String> allowedTools,
                                         McpToolScope scope) {
        List<ToolExecuteResult> results = manager.execute(new ToolExecuteCommand(
                List.of(new ToolCallRequest("call-1", MCP_TOOL, "{}")),
                "execution-1", workspace(), null, null,allowedTools, UUID.randomUUID(), false, scope));

        assertEquals(1, results.size());
        return results.getFirst();
    }

    private McpToolScope scopeWith(String toolName, AtomicInteger calls) {
        return McpToolScope.of(List.of(new McpSession() {
            @Override
            public String name() {
                return "test-server";
            }

            @Override
            public List<ToolDefinition<?>> tools() {
                return List.of(mcpDefinition(toolName, "me", calls));
            }

            @Override
            public void close() {
            }
        }));
    }

    private ToolDefinition<?> mcpDefinition(String name, String output, AtomicInteger calls) {
        return ToolDefinition.builder()
                .id(name).name(name).maxOutput(1000).timeout(0L)
                .concurrentPolicy(ConcurrentPolicy.READ_ONLY)
                .executor(execution -> {
                    calls.incrementAndGet();
                    return ToolExecuteResult.success(output);
                })
                .build();
    }

    /** A run confined to one explicit static tool, with no MCP server of its own. */
    private DefaultToolExecutionManager manager() {
        ToolDefinition<?> statics = ToolDefinition.builder()
                .id(STATIC_TOOL).name(STATIC_TOOL).maxOutput(100).timeout(0L)
                .concurrentPolicy(ConcurrentPolicy.READ_ONLY)
                .executor(execution -> ToolExecuteResult.success("content"))
                .build();
        return new DefaultToolExecutionManager(
                ToolExecutionContext.builder()
                        .toolRegistry(new ToolRegistry(List.of(statics)))
                        .runtimeEventPublisher(new RuntimeEventPublisher(List.of()))
                        .build(),
                invocation -> invocation.getMethod().invoke(invocation.getTarget(), invocation.getContext()),
                List.of());
    }

    /** The rejection message carries a trailing explanation; only its leading clause is asserted. */
    private String trimReason(String output) {
        int cut = output.indexOf(": it is not part");
        return cut < 0 ? output : output.substring(0, cut);
    }

    private Workspace workspace() {
        return new Workspace() {
            @Override
            public String id() {
                return "workspace-1";
            }

            @Override
            public RuntimeEnvironment runtimeEnvironment() {
                return null;
            }

            @Override
            public String workDir() {
                return ".";
            }

            @Override
            public Path resolve(String path) {
                return Path.of(path);
            }
        };
    }
}
