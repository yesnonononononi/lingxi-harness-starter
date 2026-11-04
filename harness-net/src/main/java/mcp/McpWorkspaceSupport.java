package mcp;

import com.summit.core.conf.McpConfig;
import com.summit.core.runtime.workspace.Workspace;
import com.summit.core.runtime.workspace.WorkspaceStdioLaunch;
import com.summit.core.runtime.workspace.WorkspaceStdioLauncher;

import java.io.IOException;
import java.util.Map;

/**
 * Workspace policy of MCP connections.
 *
 * <p>An isolated workspace owns the process: stdio only (a host-side HTTP connection would leave the
 * sandbox), no configured {@code work-dir} (the sandbox fixes the directory, and honoring a caller's
 * path would break the isolation), and a bridge that can prepare a supervised stdio session. All of
 * that is checked before discovery or process startup — never after a host-side fallback, which
 * would silently run unsandboxed.</p>
 */
public final class McpWorkspaceSupport {

    private McpWorkspaceSupport() {
    }

    /**
     * @param workspace workspace of the request, may be null
     * @return whether the workspace requires isolation for the connections it declares
     */
    public static boolean isIsolated(Workspace workspace) {
        return workspace != null && workspace.runtimeEnvironment().isolated();
    }

    /**
     * Validates every server of a request before any of them is opened, so a partially invalid
     * isolated configuration cannot leave a half-opened scope behind.
     *
     * <p>The transport and directory policy is checked for the whole configuration first, and only
     * then the workspace capability: a configuration the isolation can never honor is rejected as
     * such, whether or not the workspace could start a supervised session at all.</p>
     *
     * @param config    request configuration; a null or empty list is valid
     * @param workspace workspace of the request, may be null
     * @throws IllegalArgumentException when a server declares a transport or a directory the
     *                                  isolation cannot honor
     * @throws IllegalStateException    when the workspace cannot start a supervised stdio session
     */
    public static void validate(McpConfig config, Workspace workspace) {
        if (config == null || !isIsolated(workspace) || config.getMcp() == null || config.getMcp().isEmpty()) {
            return;
        }
        for (McpConfig.MCP server : config.getMcp()) {
            validatePolicy(server, workspace);
        }
        requireStdioLauncher(workspace);
    }

    /**
     * Validates one server against the workspace policy.
     *
     * @param server    server entry about to be opened
     * @param workspace workspace of the request, may be null
     * @throws IllegalArgumentException when the server uses a non-stdio transport or a work directory
     * @throws IllegalStateException    when the workspace cannot start a supervised stdio session
     */
    public static void validate(McpConfig.MCP server, Workspace workspace) {
        if (!validatePolicy(server, workspace)) {
            return;
        }
        requireStdioLauncher(workspace);
    }

    /** @return whether the server is subject to the isolation policy at all */
    private static boolean validatePolicy(McpConfig.MCP server, Workspace workspace) {
        if (server == null || !isIsolated(workspace)) {
            return false;
        }
        if (!(server.conf() instanceof McpConfig.Stdio stdio)) {
            throw new IllegalArgumentException("Isolated workspace permits only stdio MCP: " + server.name());
        }
        if (stdio.workDir() != null && !stdio.workDir().isBlank()) {
            throw new IllegalArgumentException(
                    "Isolated workspace fixes the MCP working directory, so work-dir is not allowed: "
                            + server.name());
        }
        return true;
    }

    private static void requireStdioLauncher(Workspace workspace) {
        if (!(workspace.bridge() instanceof WorkspaceStdioLauncher)) {
            throw new IllegalStateException("Workspace does not support isolated MCP stdio startup");
        }
    }

    /**
     * Asks the workspace bridge for a supervised stdio session.
     *
     * @param stdio     stdio settings of the server; its argv and environment are handed to the bridge
     * @param workspace workspace that must provide a {@link WorkspaceStdioLauncher}
     * @return a prepared launch recipe whose {@code close()} must run before the transport closes
     * @throws IOException              when the bridge cannot prepare the session
     * @throws IllegalStateException    when the workspace cannot start a supervised stdio session
     */
    public static WorkspaceStdioLaunch prepare(McpConfig.Stdio stdio, Workspace workspace) throws IOException {
        if (stdio == null) {
            throw new IllegalArgumentException("Isolated MCP startup requires stdio settings");
        }
        if (workspace == null || !(workspace.bridge() instanceof WorkspaceStdioLauncher launcher)) {
            throw new IllegalStateException("Workspace does not support isolated MCP stdio startup");
        }
        Map<String, String> environment = stdio.env() == null ? Map.of() : stdio.env();
        return launcher.prepareStdio(stdio.command(), environment);
    }
}
