package com.summit.adapter.langchain4j.mcp;

import com.summit.core.conf.McpConfig;
import dev.langchain4j.mcp.client.DefaultMcpClient;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Asserts the tuning decision, not the connected client: building a real client starts a transport
 * and completes protocol initialization, which needs a live server.
 *
 * <p>Rather than leaving the forwarding unchecked, the test drives {@code applyTuning} with a real
 * {@link DefaultMcpClient.Builder} and reads back the values the builder recorded in its own
 * fields. That is the exact state {@code build()} would later use, so a dropped or inverted branch
 * fails here instead of only in production.
 */
class McpClientFactoryTuningTest {

    private static final Duration INIT = Duration.ofSeconds(3);
    private static final Duration EXEC = Duration.ofSeconds(4);

    private final McpClientFactory factory = new McpClientFactory();

    /** Both connection records must forward all four values to the client builder. */
    @Test
    void httpTuningReachesTheBuilder() {
        DefaultMcpClient.Builder builder = new DefaultMcpClient.Builder();

        factory.applyTuning(builder, new McpConfig.StreamableHttp("https://mcp.context7.com/mcp", Map.of(),
                INIT, EXEC, "2025-11-25", Boolean.FALSE, Duration.ofSeconds(30), Duration.ofSeconds(5)));

        assertEquals("2025-11-25", builderField(builder, "protocolVersion"));
        assertEquals(Boolean.FALSE, builderField(builder, "autoHealthCheck"));
        assertEquals(Duration.ofSeconds(30), builderField(builder, "autoHealthCheckInterval"));
        assertEquals(Duration.ofSeconds(5), builderField(builder, "reconnectInterval"));
    }

    /** stdio undergoes the same protocol negotiation, so it must forward the same four values. */
    @Test
    void stdioTuningReachesTheBuilder() {
        DefaultMcpClient.Builder builder = new DefaultMcpClient.Builder();

        factory.applyTuning(builder, new McpConfig.Stdio(List.of("npx"), Map.of(), null, INIT, EXEC,
                "2025-11-25", Boolean.TRUE, Duration.ofMillis(1500), Duration.ofMillis(2500)));

        assertEquals("2025-11-25", builderField(builder, "protocolVersion"));
        assertEquals(Boolean.TRUE, builderField(builder, "autoHealthCheck"));
        assertEquals(Duration.ofMillis(1500), builderField(builder, "autoHealthCheckInterval"));
        assertEquals(Duration.ofMillis(2500), builderField(builder, "reconnectInterval"));
    }

    /**
     * An entry written before these fields existed carries nulls, and null must reach the builder as
     * null: setting any value there would change the behaviour of every stored configuration.
     */
    @Test
    void absentTuningLeavesTheLibraryDefaultsAlone() {
        DefaultMcpClient.Builder http = new DefaultMcpClient.Builder();
        factory.applyTuning(http, new McpConfig.StreamableHttp("https://mcp.draw.io/mcp", Map.of(),
                INIT, EXEC, null, null, null, null));

        assertNull(builderField(http, "protocolVersion"));
        assertNull(builderField(http, "autoHealthCheck"));
        assertNull(builderField(http, "autoHealthCheckInterval"));
        assertNull(builderField(http, "reconnectInterval"));

        DefaultMcpClient.Builder stdio = new DefaultMcpClient.Builder();
        factory.applyTuning(stdio, new McpConfig.Stdio(List.of("npx"), Map.of(), null, INIT, EXEC,
                null, null, null, null));

        assertNull(builderField(stdio, "protocolVersion"));
        assertNull(builderField(stdio, "autoHealthCheck"));
        assertNull(builderField(stdio, "autoHealthCheckInterval"));
        assertNull(builderField(stdio, "reconnectInterval"));
    }

    /** A blank version means "not configured", and the library keeps probing for one. */
    @Test
    void blankProtocolVersionIsNotForwarded() {
        DefaultMcpClient.Builder builder = new DefaultMcpClient.Builder();

        factory.applyTuning(builder, "   ", null, null, null);

        assertNull(builderField(builder, "protocolVersion"));
    }

    /** Stored versions may be pasted with padding; the value handed to the library is trimmed. */
    @Test
    void protocolVersionIsTrimmedBeforeItIsForwarded() {
        DefaultMcpClient.Builder builder = new DefaultMcpClient.Builder();

        factory.applyTuning(builder, " 2025-11-25 ", null, null, null);

        assertEquals("2025-11-25", builderField(builder, "protocolVersion"));
    }

    /** Each value is independent: setting one must not consume or skip the others. */
    @Test
    void tuningValuesAreAppliedIndependently() {
        DefaultMcpClient.Builder onlyHealth = new DefaultMcpClient.Builder();
        factory.applyTuning(onlyHealth, null, null, Duration.ofSeconds(60), null);
        assertNull(builderField(onlyHealth, "protocolVersion"));
        assertNull(builderField(onlyHealth, "autoHealthCheck"));
        assertEquals(Duration.ofSeconds(60), builderField(onlyHealth, "autoHealthCheckInterval"));
        assertNull(builderField(onlyHealth, "reconnectInterval"));

        DefaultMcpClient.Builder onlyReconnect = new DefaultMcpClient.Builder();
        factory.applyTuning(onlyReconnect, null, null, null, Duration.ofSeconds(7));
        assertNull(builderField(onlyReconnect, "autoHealthCheckInterval"));
        assertEquals(Duration.ofSeconds(7), builderField(onlyReconnect, "reconnectInterval"));
    }

    /** The SSE settings carry no tuning fields, so nothing may be forwarded for them. */
    @Test
    void sseConfForwardsNoTuning() {
        DefaultMcpClient.Builder builder = new DefaultMcpClient.Builder();

        factory.applyTuning(builder, new McpConfig.Sse("https://legacy.example/sse", Map.of(), INIT, EXEC));

        assertNull(builderField(builder, "protocolVersion"));
        assertNull(builderField(builder, "autoHealthCheck"));
        assertNull(builderField(builder, "autoHealthCheckInterval"));
        assertNull(builderField(builder, "reconnectInterval"));
    }

    private static Object builderField(DefaultMcpClient.Builder builder, String name) {
        try {
            Field field = DefaultMcpClient.Builder.class.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(builder);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("cannot read builder field " + name, failure);
        }
    }
}
