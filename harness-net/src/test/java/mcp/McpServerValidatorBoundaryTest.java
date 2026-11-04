package mcp;

import com.summit.core.conf.McpConfig;
import com.summit.core.conf.McpTransport;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The transport-matching half of {@link McpServerValidator}, plus the protocol-version length guard.
 *
 * <p>Each transport branch has its own {@code requireTransport} call. {@code McpServerValidatorTest}
 * covers the branch entered by an HTTP configuration declared as stdio settings; the mirror image —
 * stdio settings declared as streamable-http — reaches a different call site and used to be
 * unguarded, so a misconfiguration there would have been accepted and then opened with the wrong
 * transport.</p>
 */
class McpServerValidatorBoundaryTest {

    private static final Duration INIT = Duration.ofSeconds(3);
    private static final Duration EXEC = Duration.ofSeconds(4);

    /** The stdio branch must reject settings that were declared under another transport. */
    @Test
    void stdioSettingsDeclaredAsStreamableHttpAreRejected() {
        McpConfig.MCP server = new McpConfig.MCP("shadcn", null, McpTransport.STREAMABLE_HTTP,
                new McpConfig.Stdio(List.of("npx", "shadcn@latest", "mcp"), Map.of(), INIT, EXEC), 100);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> McpServerValidator.validate(server));
        assertTrue(error.getMessage().contains("does not match"), error.getMessage());
    }

    /** The same guard must also fire when the declared transport is SSE (an unsupported one). */
    @Test
    void stdioSettingsDeclaredAsSseAreRejected() {
        McpConfig.MCP server = new McpConfig.MCP("shadcn", null, McpTransport.SSE,
                new McpConfig.Stdio(List.of("npx"), Map.of(), INIT, EXEC), 100);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> McpServerValidator.validate(server));
        assertTrue(error.getMessage().contains("does not match"), error.getMessage());
    }

    /**
     * The 32-character length guard on {@code protocol-version} is redundant with the date pattern,
     * which is why PIT's boundary mutation of it survives: the pattern {@code \d{4}-\d{2}-\d{2}}
     * only matches ten characters, so every string of 32 or more characters is rejected by the
     * pattern anyway and both spellings of the length comparison produce the identical error.
     *
     * <p>This test records that equivalence and guards the observable behaviour on both sides of the
     * boundary: exactly 32 characters and one character more must be rejected with the same message
     * as a plainly malformed version.</p>
     */
    @Test
    void protocolVersionLengthGuardIsRedundantWithTheDatePattern() {
        String exactlyThirtyTwo = "2025-11-25" + "x".repeat(22);
        String thirtyThree = exactlyThirtyTwo + "x";
        assertEquals(32, exactlyThirtyTwo.length());
        assertEquals(33, thirtyThree.length());

        String atBoundary = messageOf(exactlyThirtyTwo);
        String aboveBoundary = messageOf(thirtyThree);
        String malformed = messageOf("abc");

        assertTrue(atBoundary.contains("protocol-version must look like 2025-11-25"), atBoundary);
        assertEquals(malformed, atBoundary,
                "the length guard adds nothing the date pattern does not already reject");
        assertEquals(malformed, aboveBoundary,
                "both spellings of the length comparison reject the same inputs identically");
    }

    private static String messageOf(String protocolVersion) {
        McpConfig.MCP server = new McpConfig.MCP("a", null, McpTransport.STREAMABLE_HTTP,
                new McpConfig.StreamableHttp("https://a.example/mcp", Map.of(), INIT, EXEC,
                        protocolVersion, null, null, null), 100);
        return assertThrows(IllegalArgumentException.class, () -> McpServerValidator.validate(server))
                .getMessage();
    }
}
