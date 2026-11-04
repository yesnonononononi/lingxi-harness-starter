package mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.summit.core.conf.McpConfig;
import com.summit.core.conf.McpTransport;
import com.summit.core.mcp.McpSession;
import com.summit.core.mcp.McpSessionType;
import com.summit.core.tool.ToolDefinition;
import com.summit.core.tool.ToolExecuteResult;
import com.summit.core.tool.ToolExecution;
import mcp.transport.McpStdioTransportBuilder;
import mcp.transport.WindowsCommandResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real stdio handshake against a real child process.
 *
 * <p>Two facts of the retired client cannot be checked with a fake transport: that the configured
 * working directory actually reaches the child, and that closing the session really removes the
 * process. Both are asserted here against a Node fixture that records its own working directory and
 * process id before answering the handshake.</p>
 *
 * <p>Close semantics differ from the retired client and the difference is recorded, not papered
 * over: the SDK's {@code closeGracefully()} destroys the process ({@code TerminateProcess} on
 * Windows), while the old implementation closed stdin and waited for the server to exit on its own.
 * A directly started child is therefore guaranteed to disappear within the 15s bound asserted
 * below. A <em>grandchild</em> started by that child is not: nothing in the protocol ties it to the
 * session, and a terminated parent never gets the chance to reap it. The fixture can spawn one on
 * request, and the test deliberately does not: asserting on it would either encode a platform
 * specific orphan behaviour or need a taskkill sweep outside the framework's contract.</p>
 */
@Timeout(120)
class McpStdioEndToEndTest {

    private static final Duration EXIT_BOUND = Duration.ofSeconds(15);
    private static final Path FIXTURE = Path.of("src", "test", "resources", "mcp", "fixture-server.mjs")
            .toAbsolutePath();

    private final ObjectMapper json = new ObjectMapper();

    @TempDir
    Path workDir;

    private McpSessionFactory factory;

    @AfterEach
    void closeFactory() {
        if (factory != null) {
            factory.close();
        }
    }

    @Test
    void startsTheChildInTheConfiguredWorkDirAndRemovesItOnClose() throws Exception {
        McpSession session;
        long pid;
        long startedAt = System.nanoTime();

        factory = factory();
        session = factory.openSession(stdioServer("plain"), null, McpSessionType.PER_REQUEST, "mcp_");
        long openMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);

        // The fixture writes both files before it answers the handshake, so an open session is
        // enough to prove where the child runs.
        Path cwdFile = workDir.resolve("child-cwd.txt");
        Path pidFile = workDir.resolve("child-pid.txt");
        assertTrue(Files.isRegularFile(cwdFile),
                "the child must run in the configured work dir, expected " + cwdFile);
        assertEquals(workDir.toRealPath(), Path.of(Files.readString(cwdFile, StandardCharsets.UTF_8).strip()).toRealPath(),
                "the child reported another working directory");
        pid = Long.parseLong(Files.readString(pidFile, StandardCharsets.UTF_8).strip());

        assertEquals(List.of("echo"), session.serverToolNames(), "the server publishes the raw name");
        assertEquals(List.of("mcp_echo"), session.tools().stream().map(ToolDefinition::name).toList(),
                "the published name carries the prefix");

        ToolDefinition<?> echo = session.tools().getFirst();
        ToolExecuteResult result = echo.executor().execute(ToolExecution.builder()
                .id("call-1").toolDefinition(echo).args("{\"value\":\"hello\"}").build());
        assertTrue(result.isSuccess(), result.getToolOutput());
        assertEquals("echo:hello", result.getToolOutput());

        session.checkHealth();

        ProcessHandle child = ProcessHandle.of(pid).orElseThrow(() ->
                new AssertionError("the fixture child " + pid + " is already gone"));
        assertTrue(child.isAlive(), "the child must still be running before the close");

        long closeStartedAt = System.nanoTime();
        session.close();
        boolean exited = awaitExit(pid, EXIT_BOUND);
        long closeMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - closeStartedAt);

        System.out.println("MCP stdio fixture pid=" + pid + " opened in " + openMillis
                + " ms, terminated=" + exited + " after " + closeMillis + " ms");
        assertTrue(exited, "the direct child " + pid + " must exit within " + EXIT_BOUND.toSeconds()
                + "s of the session close, still alive after " + closeMillis + " ms");
    }

    /**
     * A configured protocol version is an assertion on stdio, not a request: the transport offers
     * only the version it implements. A mismatch must be reported, never fatal.
     */
    @Test
    void aMismatchedConfiguredProtocolVersionDoesNotFailTheSession() throws Exception {
        McpConfig.MCP server = new McpConfig.MCP("pinned", null, McpTransport.STDIO,
                new McpConfig.Stdio(nodeCommand("plain"), Map.of(), workDir.toString(),
                        Duration.ofSeconds(20), Duration.ofSeconds(20), "2024-11-05", null, null, null), 4_000);

        factory = factory();
        McpSession session = factory.openSession(server);
        try {
            assertEquals("2025-11-25", ((McpSyncSession) session).negotiatedProtocolVersion(),
                    "the negotiated version wins; the configured one is only compared against it");
            assertEquals(List.of("echo"), session.serverToolNames());
        } finally {
            // The work dir is also the child's working directory on Windows, so the temporary
            // directory can only be removed once the process is gone.
            session.close();
            awaitExit(childPid(), EXIT_BOUND);
        }
    }

    /** @return the process id the fixture recorded, or -1 when it is unreadable */
    private long childPid() {
        Path pidFile = workDir.resolve("child-pid.txt");
        try {
            return Long.parseLong(Files.readString(pidFile, StandardCharsets.UTF_8).strip());
        } catch (IOException | NumberFormatException unreadable) {
            return -1L;
        }
    }

    private McpSessionFactory factory() {
        return new McpSessionFactory(McpClientSettings.defaults(),
                List.of(new McpStdioTransportBuilder(json)), null, json);
    }

    private McpConfig.MCP stdioServer(String mode) {
        return new McpConfig.MCP("fixture", null, McpTransport.STDIO,
                new McpConfig.Stdio(nodeCommand(mode), Map.of("HARNESS_FIXTURE", "1"), workDir.toString(),
                        Duration.ofSeconds(20), Duration.ofSeconds(20)), 4_000);
    }

    /** {@code [node, fixture, mode]}, already Windows-startable. */
    private List<String> nodeCommand(String mode) {
        Assumptions.assumeTrue(Files.isRegularFile(FIXTURE), "missing fixture script " + FIXTURE);
        List<String> command = WindowsCommandResolver.resolve(List.of("node", FIXTURE.toString(), mode));
        Assumptions.assumeTrue(isStartable(command.get(0)),
                "node is not available on this machine, the stdio end-to-end test is skipped");
        return command;
    }

    private static boolean isStartable(String executable) {
        try {
            Process probe = new ProcessBuilder(executable, "--version")
                    .redirectErrorStream(true).start();
            probe.getInputStream().readAllBytes();
            return probe.waitFor(20, TimeUnit.SECONDS) && probe.exitValue() == 0;
        } catch (IOException | InterruptedException unavailable) {
            if (unavailable instanceof InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            }
            return false;
        }
    }

    /** Polls the process handle: {@code destroy()} is asynchronous on every platform. */
    private static boolean awaitExit(long pid, Duration bound) {
        long deadline = System.nanoTime() + bound.toNanos();
        while (System.nanoTime() < deadline) {
            Optional<ProcessHandle> handle = ProcessHandle.of(pid);
            if (handle.isEmpty() || !handle.get().isAlive()) {
                return true;
            }
            try {
                Thread.sleep(50L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return ProcessHandle.of(pid).map(process -> !process.isAlive()).orElse(true);
            }
        }
        return ProcessHandle.of(pid).map(process -> !process.isAlive()).orElse(true);
    }
}
