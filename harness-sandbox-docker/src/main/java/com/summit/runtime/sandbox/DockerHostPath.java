package com.summit.runtime.sandbox;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;

/**
 * Host-side path handling for bind mounts.
 *
 * <p>Two forms are needed: {@link #mountPath(String)} renders a host directory
 * for the docker CLI, while {@link #normalize(String)} produces the comparison
 * form used to recognize an already-mounted directory. Both sides of a mount
 * comparison must pass through {@link #normalize(String)} before being handed
 * to {@link #same(String, String)}.</p>
 */
public final class DockerHostPath {

    private DockerHostPath() {
    }

    /**
     * Renders a host directory for a {@code -v} mount: absolute, normalized and
     * with forward slashes, which the docker CLI accepts on every host OS.
     */
    public static String mountPath(String hostDir) {
        Path path = Paths.get(hostDir).toAbsolutePath().normalize();
        return path.toString().replace('\\', '/');
    }

    /**
     * Normalizes a host path for comparison: translates docker-for-Windows
     * internal mount prefixes back to their native drive form, converts
     * backslashes to forward slashes and strips trailing slashes (keeping a
     * single slash for the filesystem root).
     */
    public static String normalize(String path) {
        String normalized = translateDockerHostPath(path.trim()).replace('\\', '/');
        while (normalized.endsWith("/") && normalized.length() > 1) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    /**
     * Compares two {@link #normalize(String) normalized} host paths. On a
     * Windows host the comparison is case-insensitive (docker may report
     * {@code D:\...} while the caller supplies {@code d:/...}); elsewhere it is
     * case-sensitive.
     */
    public static boolean same(String left, String right) {
        return isWindowsHost()
                ? left.toLowerCase(Locale.ROOT).equals(right.toLowerCase(Locale.ROOT))
                : left.equals(right);
    }

    public static boolean isWindowsHost() {
        return File.separatorChar == '\\';
    }

    /**
     * Docker Desktop (WSL2 / legacy Hyper-V backend) reports bind-mount sources
     * in an internal form such as {@code /run/desktop/mnt/host/d/Code/...} or
     * {@code /host_mnt/d/Code/...}. Those are mapped back to {@code D:/Code/...}
     * so they can be compared with a native Windows path supplied by the user.
     * The {@code /mnt/<drive>} form is only translated on a Windows host, where
     * it cannot be a genuine Linux directory.
     */
    private static String translateDockerHostPath(String path) {
        if (path.startsWith("/run/desktop/mnt/host/")) {
            return toDrivePath(path, "/run/desktop/mnt/host/".length());
        }
        if (path.startsWith("/host_mnt/")) {
            return toDrivePath(path, "/host_mnt/".length());
        }
        if (isWindowsHost() && path.startsWith("/mnt/")) {
            return toDrivePath(path, "/mnt/".length());
        }
        return path;
    }

    /**
     * Converts an internal docker host path whose mount prefix ends just before
     * a drive letter into its native form ({@code /run/desktop/mnt/host/d/...}
     * -&gt; {@code D:/...}). Returns the input unchanged when the character after
     * the prefix is not a drive letter.
     */
    private static String toDrivePath(String path, int driveIndex) {
        if (driveIndex >= path.length()) {
            return path;
        }
        char drive = path.charAt(driveIndex);
        boolean isLetter = (drive >= 'a' && drive <= 'z') || (drive >= 'A' && drive <= 'Z');
        if (!isLetter) {
            return path;
        }
        if (driveIndex + 1 < path.length() && path.charAt(driveIndex + 1) != '/') {
            return path;
        }
        String rest = driveIndex + 1 < path.length() ? path.substring(driveIndex + 1) : "/";
        return Character.toUpperCase(drive) + ":" + rest;
    }
}
