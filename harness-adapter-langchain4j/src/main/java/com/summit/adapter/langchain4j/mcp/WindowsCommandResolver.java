package com.summit.adapter.langchain4j.mcp;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Resolves a stdio launch command so that it is directly startable by {@link ProcessBuilder} on the
 * current platform.
 *
 * <p>{@code ProcessBuilder} performs no {@code PATH} lookup and no extension probing on Windows: it
 * hands the first argv element to {@code CreateProcess}, which accepts only a PE image or a file
 * whose extension is listed in {@code PATHEXT}. A POSIX-style {@code ["npx", "..."]} therefore
 * throws {@code CreateProcess error=2} even though {@code npx} resolves in a shell, and the server
 * is silently skipped. When the leading token has no extension and a startable sibling exists on
 * {@code PATH}, that sibling is substituted; otherwise the token is left untouched so a genuine
 * misconfiguration is still reported as-is.
 */
final class WindowsCommandResolver {

    /** Launcher suffixes Windows can execute directly; probing is limited to these. */
    private static final List<String> SCRIPT_SUFFIXES = List.of(".cmd", ".bat", ".exe");

    private WindowsCommandResolver() {
    }

    /**
     * @return the command with a Windows-startable executable, or the input list when no rewrite
     *         applies (non-Windows platform, already-suffixed executable, or no sibling found)
     */
    static List<String> resolve(List<String> command) {
        if (command == null || command.isEmpty() || !isWindows()) {
            return command;
        }
        List<String> resolved = new ArrayList<>(command);
        resolved.set(0, resolveExecutable(command.get(0)));
        return resolved;
    }

    private static String resolveExecutable(String executable) {
        if (executable == null || executable.isBlank()) {
            return executable;
        }
        if (hasExecutableExtension(executable)) {
            return executable;
        }
        String fromPath = findOnPath(executable);
        return fromPath != null ? fromPath : executable;
    }

    private static boolean hasExecutableExtension(String executable) {
        String lower = executable.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".exe") || lower.endsWith(".com")) {
            return true;
        }
        // "foo.js" still needs a launcher, so only PATHEXT-image suffixes count here.
        return lower.endsWith(".cmd") || lower.endsWith(".bat");
    }

    /** Walks {@code PATH} for a startable sibling of {@code executable}, plus the current directory. */
    private static String findOnPath(String executable) {
        List<File> dirs = new ArrayList<>();
        String path = System.getenv("PATH");
        if (path != null && !path.isBlank()) {
            for (String entry : path.split(File.pathSeparator)) {
                if (!entry.isBlank()) {
                    dirs.add(new File(entry));
                }
            }
        }
        dirs.add(new File("."));

        for (File dir : dirs) {
            File plain = new File(dir, executable);
            if (plain.isFile()) {
                return plain.getPath();
            }
            for (String suffix : SCRIPT_SUFFIXES) {
                File candidate = new File(dir, executable + suffix);
                if (candidate.isFile()) {
                    return candidate.getPath();
                }
            }
        }
        return null;
    }

    static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }
}
