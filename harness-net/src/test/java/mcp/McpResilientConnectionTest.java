package mcp;

import io.modelcontextprotocol.client.McpSyncClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reconnect and publication rules of one connection.
 *
 * <p>Handles are counted, so "how many clients were created" is a first-class assertion: the bug
 * this guards against is a background task publishing a fresh client into a connection that is
 * already closing, and the only reliable witness of that is the creation count.</p>
 */
@Timeout(60)
class McpResilientConnectionTest {

    private static final int ATTEMPTS = 3;

    @Test
    void aFailedPingRebuildsTheClientOnce() {
        List<FakeMcpTransport> transports = new ArrayList<>();
        AtomicInteger creations = new AtomicInteger();
        AtomicInteger closes = new AtomicInteger();
        McpResilientConnection connection = connectionOf(transports, creations, closes, 0);
        try {
            assertTrue(connection.isAvailable());
            assertEquals(1, creations.get());

            transports.getFirst().failPings(true);
            connection.checkHealth();

            assertTrue(connection.isAvailable(), "a single rebuild must restore the connection");
            assertEquals(2, creations.get());
            assertEquals(1, transports.getFirst().closeCount(),
                    "the replaced transport must be closed; the client closes it gracefully");
            assertNotNull(connection.client());
        } finally {
            connection.close();
        }
    }

    /** Everything succeeding on the second attempt means reconnect() must not exhaust its budget. */
    @Test
    void reconnectReturnsTrueWhenALaterAttemptSucceeds() {
        List<FakeMcpTransport> transports = new ArrayList<>();
        AtomicInteger creations = new AtomicInteger();
        AtomicInteger closes = new AtomicInteger();
        AtomicInteger failuresLeft = new AtomicInteger();

        McpResilientConnection connection = new McpResilientConnection(() -> {
            creations.incrementAndGet();
            if (failuresLeft.getAndDecrement() > 0) {
                throw new IllegalStateException("endpoint unreachable");
            }
            FakeMcpTransport transport = new FakeMcpTransport();
            transports.add(transport);
            return new McpTestSupport.CountingHandle(
                    McpSessionFactory.connect(transport, McpTestSupport.fastSettings(ATTEMPTS),
                            McpTestSupport.httpServer("flaky", "https://flaky.example/mcp")),
                    closes);
        }, McpTestSupport.fastSettings(ATTEMPTS), null);
        connection.start();
        connection.applyReconnectInterval(Duration.ofMillis(1));
        try {
            // The first rebuild attempt fails, the second one succeeds.
            failuresLeft.set(1);

            assertTrue(connection.reconnect(), "the second attempt must succeed");
            assertEquals(3, creations.get(), "one initial handle, one failed attempt, one good attempt");
            assertEquals(1, transports.getFirst().closeCount(), "the replaced transport must be closed");
            assertTrue(connection.isAvailable());
        } finally {
            connection.close();
        }
    }

    /** After the bounded attempts the connection reports itself unavailable instead of retrying forever. */
    @Test
    void exhaustedAttemptsMarkTheConnectionUnavailable() {
        List<FakeMcpTransport> transports = new ArrayList<>();
        AtomicInteger creations = new AtomicInteger();
        AtomicInteger closes = new AtomicInteger();
        AtomicInteger failuresLeft = new AtomicInteger(0);
        McpResilientConnection connection = connectionOf(transports, creations, closes, failuresLeft);
        try {
            transports.getFirst().failPings(true);
            failuresLeft.set(ATTEMPTS);

            connection.checkHealth();

            assertFalse(connection.isAvailable());
            assertEquals(1 + ATTEMPTS, creations.get(), "one initial handle plus the bounded attempts");
            assertEquals(1, transports.getFirst().closeCount(), "the dead transport must be released");
            assertThrows(IllegalStateException.class, connection::client);
        } finally {
            connection.close();
        }
        assertTrue(connection.isClosed());
    }

    /** A closing connection must never publish, no matter who calls ping or reconnect. */
    @Test
    void aClosedConnectionNeverCreatesAnotherClient() {
        List<FakeMcpTransport> transports = new ArrayList<>();
        AtomicInteger creations = new AtomicInteger();
        AtomicInteger closes = new AtomicInteger();
        McpResilientConnection connection = connectionOf(transports, creations, closes, 0);
        connection.close();

        assertFalse(connection.reconnect(), "a closed connection has nothing to rebuild");
        connection.checkHealth();
        assertEquals(1, creations.get(), "no client may be created after close");
        assertEquals(1, transports.getFirst().closeCount());
        assertFalse(connection.isAvailable());
    }

    @Test
    void closeIsIdempotent() {
        List<FakeMcpTransport> transports = new ArrayList<>();
        AtomicInteger creations = new AtomicInteger();
        AtomicInteger closes = new AtomicInteger();
        McpResilientConnection connection = connectionOf(transports, creations, closes, 0);

        connection.close();
        connection.close();
        connection.close();

        assertEquals(1, transports.getFirst().closeCount(), "the transport must be released exactly once");
    }

    /** A connection with no handle at all must report unavailability rather than a null pointer. */
    @Test
    void aClosedConnectionReportsUnavailabilityAndAClientlessStartIsRejected() {
        AtomicInteger closes = new AtomicInteger();
        McpResilientConnection connection = new McpResilientConnection(() -> {
            FakeMcpTransport transport = new FakeMcpTransport();
            return new McpTestSupport.CountingHandle(McpSessionFactory.connect(transport,
                    McpTestSupport.fastSettings(ATTEMPTS),
                    McpTestSupport.httpServer("once", "https://once.example/mcp")), closes);
        }, McpTestSupport.fastSettings(ATTEMPTS), null);
        connection.start();
        connection.close();

        assertThrows(IllegalStateException.class, connection::client);

        McpResilientConnection clientless = new McpResilientConnection(() -> null,
                McpTestSupport.fastSettings(ATTEMPTS), null);
        assertThrows(IllegalStateException.class, clientless::start);
    }

    /**
     * Builds a connection whose handle factory records every transport it creates; the failure
     * budget decides how many of the follow-up creations throw.
     */
    private static McpResilientConnection connectionOf(List<FakeMcpTransport> transports,
                                                      AtomicInteger creations, AtomicInteger closes,
                                                      AtomicInteger failuresLeft) {
        McpResilientConnection connection = new McpResilientConnection(() -> {
            creations.incrementAndGet();
            if (failuresLeft.getAndDecrement() > 0) {
                throw new IllegalStateException("endpoint unreachable");
            }
            FakeMcpTransport transport = new FakeMcpTransport();
            transports.add(transport);
            return new McpTestSupport.CountingHandle(McpSessionFactory.connect(transport,
                    McpTestSupport.fastSettings(ATTEMPTS),
                    McpTestSupport.httpServer("flaky", "https://flaky.example/mcp")), closes);
        }, McpTestSupport.fastSettings(ATTEMPTS), null);
        connection.start();
        connection.applyReconnectInterval(Duration.ofMillis(1));
        return connection;
    }

    private static McpResilientConnection connectionOf(List<FakeMcpTransport> transports,
                                                       AtomicInteger creations, AtomicInteger closes,
                                                       int failuresLeft) {
        return connectionOf(transports, creations, closes, new AtomicInteger(failuresLeft));
    }
}
