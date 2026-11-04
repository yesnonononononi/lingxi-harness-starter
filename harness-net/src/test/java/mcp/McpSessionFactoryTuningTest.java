package mcp;

import com.summit.core.conf.McpConfig;
import com.summit.core.conf.McpTransport;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Asserts the tuning decision, not a connected client: opening a real session needs a live server.
 *
 * <p>The retired implementation forwarded four values into its client builder; this one resolves
 * them into the settings the health-check and reconnect machinery reads, so the same values are
 * checked at the same place: a dropped or inverted branch fails here instead of only in production.
 * The protocol version is a transport concern now and is covered by {@code McpHttpTransportBuilderTest}.</p>
 */
class McpSessionFactoryTuningTest {

    private static final Duration INIT = Duration.ofSeconds(3);
    private static final Duration EXEC = Duration.ofSeconds(4);

    private final McpClientSettings settings = McpClientSettings.defaults();

    /** Both connection records must forward all three tuning values. */
    @Test
    void httpTuningReachesTheSettings() {
        McpConfig.StreamableHttp http = new McpConfig.StreamableHttp("https://mcp.context7.com/mcp", Map.of(),
                INIT, EXEC, "2025-11-25", Boolean.FALSE, Duration.ofSeconds(30), Duration.ofSeconds(5));

        assertFalse(settings.resolveAutoHealthCheck(http));
        assertEquals(Duration.ofSeconds(30), settings.resolveHealthCheckInterval(http));
        assertEquals(Duration.ofSeconds(5), settings.resolveReconnectInterval(http));
        assertEquals("2025-11-25", settings.resolveProtocolVersion(http));
    }

    /** stdio is tuned through the same fields, so it must resolve the same way. */
    @Test
    void stdioTuningReachesTheSettings() {
        McpConfig.Stdio stdio = new McpConfig.Stdio(List.of("npx"), Map.of(), null, INIT, EXEC,
                "2025-11-25", Boolean.TRUE, Duration.ofMillis(1500), Duration.ofMillis(2500));

        assertTrue(settings.resolveAutoHealthCheck(stdio));
        assertEquals(Duration.ofMillis(1500), settings.resolveHealthCheckInterval(stdio));
        assertEquals(Duration.ofMillis(2500), settings.resolveReconnectInterval(stdio));
        assertEquals("2025-11-25", settings.resolveProtocolVersion(stdio));
    }

    /**
     * An entry written before these fields existed carries nulls, and null must keep the documented
     * defaults: substituting anything else would change the behaviour of every stored configuration.
     */
    @Test
    void absentTuningFallsBackToTheDocumentedDefaults() {
        McpConfig.StreamableHttp http = new McpConfig.StreamableHttp("https://mcp.draw.io/mcp", Map.of(),
                INIT, EXEC, null, null, null, null);
        McpConfig.Stdio stdio = new McpConfig.Stdio(List.of("npx"), Map.of(), null, INIT, EXEC,
                null, null, null, null);

        for (McpConfig.Conf conf : List.of(http, stdio)) {
            assertTrue(settings.resolveAutoHealthCheck(conf), "health checking is on by default");
            assertEquals(Duration.ofSeconds(30), settings.resolveHealthCheckInterval(conf));
            assertEquals(Duration.ofSeconds(5), settings.resolveReconnectInterval(conf));
            assertEquals("", settings.resolveProtocolVersion(conf));
        }
        assertEquals(3, settings.reconnectAttempts());
        assertEquals(2_000L, settings.backoffBaseMillis());
        assertEquals(2, settings.backoffMultiple());
        assertEquals(60_000L, settings.backoffCapMillis());
    }

    /** A blank version means "not configured": the transport keeps probing for one. */
    @Test
    void blankProtocolVersionResolvesToEmpty() {
        assertEquals("", settings.resolveProtocolVersion(
                new McpConfig.StreamableHttp("https://a.example/mcp", Map.of(), INIT, EXEC,
                        "   ", null, null, null)));
    }

    /** Stored versions may be pasted with padding; the value handed to the transport is trimmed. */
    @Test
    void protocolVersionIsTrimmed() {
        assertEquals("2025-11-25", settings.resolveProtocolVersion(
                new McpConfig.StreamableHttp("https://a.example/mcp", Map.of(), INIT, EXEC,
                        " 2025-11-25 ", null, null, null)));
    }

    /** Each value is independent: setting one must not consume or skip the others. */
    @Test
    void tuningValuesAreAppliedIndependently() {
        McpConfig.StreamableHttp onlyHealth = new McpConfig.StreamableHttp("https://a.example/mcp", Map.of(),
                INIT, EXEC, null, null, Duration.ofSeconds(60), null);
        assertTrue(settings.resolveAutoHealthCheck(onlyHealth));
        assertEquals(Duration.ofSeconds(60), settings.resolveHealthCheckInterval(onlyHealth));
        assertEquals(Duration.ofSeconds(5), settings.resolveReconnectInterval(onlyHealth));

        McpConfig.StreamableHttp onlyReconnect = new McpConfig.StreamableHttp("https://a.example/mcp", Map.of(),
                INIT, EXEC, null, null, null, Duration.ofSeconds(7));
        assertEquals(Duration.ofSeconds(30), settings.resolveHealthCheckInterval(onlyReconnect));
        assertEquals(Duration.ofSeconds(7), settings.resolveReconnectInterval(onlyReconnect));
    }

    /** The SSE settings carry no tuning fields, so nothing may be resolved from them but defaults. */
    @Test
    void sseConfResolvesToDefaults() {
        McpConfig.Sse sse = new McpConfig.Sse("https://legacy.example/sse", Map.of(), INIT, EXEC);

        assertTrue(settings.resolveAutoHealthCheck(sse));
        assertEquals(Duration.ofSeconds(30), settings.resolveHealthCheckInterval(sse));
        assertEquals(Duration.ofSeconds(5), settings.resolveReconnectInterval(sse));
        assertEquals("", settings.resolveProtocolVersion(sse));
    }

    /** A caller-provided settings record replaces the defaults, and the entry still wins. */
    @Test
    void customDefaultsAreOverriddenByTheEntry() {
        McpClientSettings custom = new McpClientSettings("custom", "9.9.9", Duration.ofSeconds(10),
                Duration.ofSeconds(20), false, Duration.ofSeconds(11), Duration.ofSeconds(12), 5,
                10L, 3, 100L);

        McpConfig.StreamableHttp bare = new McpConfig.StreamableHttp("https://a.example/mcp", Map.of(),
                INIT, EXEC, null, null, null, null);
        assertFalse(custom.resolveAutoHealthCheck(bare));
        assertEquals(Duration.ofSeconds(11), custom.resolveHealthCheckInterval(bare));
        assertEquals(Duration.ofSeconds(12), custom.resolveReconnectInterval(bare));

        McpConfig.StreamableHttp tuned = new McpConfig.StreamableHttp("https://a.example/mcp", Map.of(),
                INIT, EXEC, null, Boolean.TRUE, Duration.ofSeconds(40), Duration.ofSeconds(41));
        assertTrue(custom.resolveAutoHealthCheck(tuned));
        assertEquals(Duration.ofSeconds(40), custom.resolveHealthCheckInterval(tuned));
        assertEquals(Duration.ofSeconds(41), custom.resolveReconnectInterval(tuned));
    }

    /** The server timeouts win when present and fall back to the settings when absent. */
    @Test
    void serverTimeoutsWinOverTheDefaults() {
        McpConfig.MCP server = new McpConfig.MCP("a", null, McpTransport.STREAMABLE_HTTP,
                new McpConfig.StreamableHttp("https://a.example/mcp", Map.of(), INIT, EXEC), 100);

        assertEquals(INIT, settings.resolveInitializationTimeout(server));
        assertEquals(EXEC, settings.resolveRequestTimeout(server));
        assertEquals(Duration.ofSeconds(30),
                settings.resolveInitializationTimeout(null));
        assertEquals(Duration.ofSeconds(60), settings.resolveRequestTimeout(null));
    }

    /** A half-written settings record must not open a window of unusable values. */
    @Test
    void settingsNormalizeUnusableValues() {
        McpClientSettings broken = new McpClientSettings(" ", null, Duration.ZERO, Duration.ofMillis(-1),
                true, null, Duration.ZERO, 0, -5L, 0, -1L);

        assertEquals(McpClientSettings.DEFAULT_CLIENT_NAME, broken.clientName());
        assertEquals(McpClientSettings.DEFAULT_CLIENT_VERSION, broken.clientVersion());
        assertEquals(Duration.ofSeconds(30), broken.defaultInitializationTimeout());
        assertEquals(Duration.ofSeconds(60), broken.defaultRequestTimeout());
        assertEquals(1, broken.reconnectAttempts());
        assertEquals(0L, broken.backoffBaseMillis());
        assertEquals(1, broken.backoffMultiple());
        assertEquals(0L, broken.backoffCapMillis());
    }
}
