package mcp.transport;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The Windows rewrite is what makes {@code npx}-style servers startable at all, so its rules are
 * asserted on both kinds of platform: on POSIX nothing may be rewritten, on Windows an
 * extension-less executable must be replaced by a startable sibling — or left alone when there is
 * none, so a genuine misconfiguration is still reported as one.
 */
class WindowsCommandResolverTest {

    private static final String NOT_ON_PATH = "definitely-not-a-real-executable-lx";

    @Test
    void nullAndEmptyCommandsArePassedThrough() {
        assertNull(WindowsCommandResolver.resolve(null));
        assertEquals(List.of(), WindowsCommandResolver.resolve(List.of()));
    }

    @Test
    void posixPlatformsAreNeverRewritten() {
        assumeFalse(WindowsCommandResolver.isWindows(), "POSIX only");
        List<String> command = List.of("npx", "shadcn@latest", "mcp");
        assertSame(command, WindowsCommandResolver.resolve(command));
    }

    /** A launcher already carrying a startable suffix is left exactly as configured. */
    @Test
    void suffixedExecutablesAreLeftAlone() {
        for (String executable : List.of("C:/tools/run.exe", "C:/tools/run.cmd", "C:/tools/run.bat",
                "C:/tools/run.com")) {
            List<String> command = List.of(executable, "--flag");
            assertEquals(command, WindowsCommandResolver.resolve(command));
        }
    }

    /** An executable nobody provides must survive untouched: the failure has to name the real argv. */
    @Test
    void unknownExecutablesAreLeftAlone() {
        List<String> command = List.of(NOT_ON_PATH, "arg");
        assertEquals(command, WindowsCommandResolver.resolve(command));
    }

    @Test
    void windowsFindsAStartableSiblingOnThePath() {
        assumeTrue(WindowsCommandResolver.isWindows(), "The PATH rewrite only exists on Windows");
        List<String> resolved = WindowsCommandResolver.resolve(List.of("cmd", "/c", "echo hi"));

        assertEquals(3, resolved.size());
        assertEquals("/c", resolved.get(1));
        assertEquals("echo hi", resolved.get(2));
        String executable = resolved.get(0).toLowerCase(Locale.ROOT);
        assertTrue(executable.endsWith("cmd.exe"), "expected cmd.exe, got " + resolved.get(0));
    }

    /** Only the executable may be rewritten; arguments with spaces and shell metacharacters stay put. */
    @Test
    void windowsKeepsArgumentsUntouchedWhileRewritingOnlyTheExecutable() {
        assumeTrue(WindowsCommandResolver.isWindows(), "The PATH rewrite only exists on Windows");
        List<String> resolved = WindowsCommandResolver.resolve(List.of("cmd", "hello world", "a&b"));

        assertEquals("hello world", resolved.get(1));
        assertEquals("a&b", resolved.get(2));
    }
}
