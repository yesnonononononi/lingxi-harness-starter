package mcp.transport;

import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.McpJsonMapper;

import java.nio.file.Path;

/**
 * {@link StdioClientTransport} that starts the child in a configured working directory.
 *
 * <p>The SDK gives no configuration entry for the child's directory, but it builds the
 * {@link ProcessBuilder} before it sets the command and the environment — so a subclass that only
 * sets {@code directory} cannot be overwritten by the SDK, and the command and environment still
 * arrive intact. That ordering is what makes this override safe rather than fragile.</p>
 */
public class McpStdioTransport extends StdioClientTransport {

    private final Path workDir;

    /**
     * @param params     command, arguments and environment of the child process
     * @param jsonMapper mapper of the JSON-RPC framing; must be the application's, not the SDK default
     * @param workDir    directory the child starts in, or null to inherit the host process directory
     */
    public McpStdioTransport(ServerParameters params, McpJsonMapper jsonMapper, Path workDir) {
        super(params, jsonMapper);
        this.workDir = workDir;
    }

    /** @return the configured working directory, or null when the child inherits the host one */
    protected Path workDir() {
        return workDir;
    }

    @Override
    protected ProcessBuilder getProcessBuilder() {
        ProcessBuilder builder = super.getProcessBuilder();
        if (workDir != null) {
            builder.directory(workDir.toFile());
        }
        return builder;
    }
}
