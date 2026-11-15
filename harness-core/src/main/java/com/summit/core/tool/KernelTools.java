package com.summit.core.tool;

import com.summit.core.mcp.McpToolGateway;

import java.util.Set;

/**
 * The tools the framework itself registers, and therefore the ones a request never has to ask for.
 *
 * <p><b>Why they are exempt from the whitelist.</b> Compaction, MCP discovery, the MCP call gateway
 * and skill loading are properties of running an agent loop, not capabilities the application hands
 * the model. Requiring every caller to repeat these five names was ceremony that broke in a
 * predictable way — a run that forgot one lost the feature silently, and a run that forgot the
 * gateway could look a remote tool up and then not call it. The whitelist therefore governs the
 * application's own tools; these are shipped by {@code harness-kernel-tools} and removed, if at all,
 * through their own {@code lingxi.agent.runtime.tool.*.enabled} switches.</p>
 *
 * <p>The names live here rather than next to their executors so the exemption needs no dependency
 * from {@code harness-core} up into {@code harness-kernel-tools}; the executors read these constants
 * instead of declaring their own, so a rename stays a compile error rather than a silent miss.</p>
 */
public final class KernelTools {

    public static final String SEARCH = McpToolGateway.DISCOVERY_TOOL;
    public static final String LIST_MCP = "list_mcp_tools";
    public static final String CALL_MCP = McpToolGateway.NAME;
    public static final String READ_SKILL = "read_skill";
    public static final String COMPACT_CONTEXT = "compact_context";

    private static final Set<String> NAMES = Set.of(SEARCH, LIST_MCP, CALL_MCP, READ_SKILL, COMPACT_CONTEXT);

    private KernelTools() {
    }

    /** Whether the framework registers this tool itself, which is what exempts it from the whitelist. */
    public static boolean isKernel(String toolName) {
        return toolName != null && NAMES.contains(toolName);
    }
}
