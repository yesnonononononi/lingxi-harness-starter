package mcp.transport;

import com.summit.core.runtime.workspace.WorkspaceStdioLaunch;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.util.List;
import java.util.function.Function;

/**
 * Sandbox-owned stdio transport: the launch recipe is released before the supervising process is
 * terminated.
 *
 * <p>The sandbox prepares a launch recipe that owns the whole session — its control files, its
 * helper directory and the process tree underneath. Closing the transport first would leave that
 * recipe behind and, on some platforms, leave the sandboxed grandchildren orphaned; closing the
 * recipe first tears down the tree in the order the sandbox expects, and the transport then closes
 * what is left of the pipe. The order is enforced here rather than by every caller, and closing is
 * idempotent because a session may be closed by its scope, by a reconnect and by the factory.</p>
 */
@Slf4j
public class McpOwnedStdioTransport extends McpStdioTransport {

    private final WorkspaceStdioLaunch launch;

    private volatile boolean closed;

    /**
     * @param launch     launch recipe prepared by the workspace bridge
     * @param jsonMapper mapper of the JSON-RPC framing
     */
    public McpOwnedStdioTransport(WorkspaceStdioLaunch launch, McpJsonMapper jsonMapper) {
        super(parametersOf(launch), jsonMapper, null);
        this.launch = launch;
    }

    @Override
    public Mono<Void> connect(
            Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>> handler) {
        if (closed) {
            throw new IllegalStateException("MCP stdio session is closed");
        }
        return super.connect(handler);
    }

    @Override
    public Mono<Void> closeGracefully() {
        if (closed) {
            return Mono.empty();
        }
        closed = true;
        closeLaunch();
        return super.closeGracefully();
    }

    /** @return whether this transport has already been closed */
    public boolean isClosed() {
        return closed;
    }

    /** Releases the sandbox session first; a failure here must not prevent the pipe from closing. */
    private void closeLaunch() {
        try {
            launch.close();
        } catch (IOException failure) {
            log.warn("MCP sandbox launch close failed ({})", failure.getClass().getSimpleName());
        }
    }

    /** @return the sandbox recipe argv and environment as SDK server parameters */
    private static ServerParameters parametersOf(WorkspaceStdioLaunch launch) {
        List<String> command = launch.command();
        if (command.isEmpty()) {
            throw new IllegalArgumentException("Sandbox stdio launch carries no command");
        }
        return ServerParameters.builder(command.get(0))
                .args(command.subList(1, command.size()))
                .env(launch.environment())
                .build();
    }
}
