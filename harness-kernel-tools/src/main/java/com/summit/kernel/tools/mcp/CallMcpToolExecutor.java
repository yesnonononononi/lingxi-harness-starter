package com.summit.kernel.tools.mcp;

import com.summit.core.mcp.McpToolGateway;
import com.summit.core.tool.KernelTools;
import com.summit.core.tool.ToolExecuteResult;
import com.summit.core.tool.ToolExecution;
import com.summit.core.tool.ToolExecutor;
import com.summit.core.tool.ToolRegistry;
import lombok.NonNull;

import java.util.function.Supplier;

/**
 * Answers a gateway call that the execution entry could not rewrite.
 *
 * <p>A resolvable gateway call never reaches this executor: it is rewritten into the call of the
 * remote tool it names before anything resolves it, so admission, concurrency, timeout, output
 * truncation and tool-call events all see that tool. Reaching here therefore means the call names
 * nothing callable — an unknown, built-in or recursive name, or a run without an MCP server — and
 * the only useful answer is why, phrased so the model can correct its next call.</p>
 */
public class CallMcpToolExecutor implements ToolExecutor {

    /** Model-facing tool name; the prompt and the lookup's {@code remote} flag refer to it. */
    public static final String NAME = KernelTools.CALL_MCP;

    /** Resolved lazily: the registry holds this tool, so an eager reference would close a cycle. */
    private final Supplier<ToolRegistry> toolRegistry;

    public CallMcpToolExecutor(Supplier<ToolRegistry> toolRegistry) {
        this.toolRegistry = toolRegistry;
    }

    @Override
    public @NonNull ToolExecuteResult execute(ToolExecution toolExecution) {
        ToolRegistry registry = toolRegistry == null ? null : toolRegistry.get();
        return switch (McpToolGateway.resolve(toolExecution.getArgs(), toolExecution.requireMcpToolScope(), registry)) {
            case McpToolGateway.Rejected rejected -> ToolExecuteResult.err(rejected.reason());
            // A caller outside the runtime hands the envelope here unforwarded; calling the named
            // remote tool is the runtime's job, not this executor's.
            case McpToolGateway.Target target -> ToolExecuteResult.err(
                    "call_mcp_tool cannot call '" + target.name() + "' on its own");
        };
    }
}
