package com.summit.runtime.sandbox;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Container-internal path handling shared by {@link DockerWorkspace} and
 * {@link DockerWorkspaceBridge}.
 *
 * <p>Container paths are POSIX regardless of the host OS, so enclosure checks
 * are done on the string form: {@code Paths#get} on a Windows host would
 * reinterpret {@code /workspace} with host semantics.</p>
 */
public final class DockerContainerPath {

    private DockerContainerPath() {
    }

    /**
     * Renders a workspace path as a POSIX container path. On a Windows host the
     * resolved {@link Path} contains backslashes, which docker cannot map to
     * container-internal paths.
     */
    public static String posix(Path path) {
        return path.toString().replace('\\', '/');
    }

    /** Resolves {@code path} against {@code workspaceRoot}, confining it to the workspace. */
    public static Path resolve(String workspaceRoot, String path) {
        Path root = Paths.get(workspaceRoot).normalize();
        if (path == null || path.isEmpty()) {
            return root;
        }
        // An absolute path outside the root is re-anchored below it.
        if (path.startsWith("/")) {
            Path absolute = Paths.get(path).normalize();
            return absolute.startsWith(root) ? absolute : root.resolve(path.substring(1)).normalize();
        }
        return root.resolve(path).normalize();
    }

    /** Whether {@code path} is the workspace root itself or lives below it. */
    public static boolean encloses(String workspaceRoot, Path path) {
        if (path == null) {
            return false;
        }
        String normalized = posix(path);
        if (!normalized.startsWith("/")) {
            normalized = "/" + normalized;
        }
        while (normalized.contains("//")) {
            normalized = normalized.replace("//", "/");
        }
        if (normalized.length() > 1 && normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized.equals(workspaceRoot) || normalized.startsWith(workspaceRoot + "/");
    }
}
