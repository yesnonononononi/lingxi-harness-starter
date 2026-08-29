package com.summit.runtime.sandbox;

import com.summit.harnesscore.runtime.OsType;
import com.summit.harnesscore.runtime.RuntimeEnvironment;
import com.summit.harnesscore.runtime.ShellType;
import com.summit.harnesscore.runtime.Workspace;
import com.summit.harnesscore.runtime.WorkspaceBridge;
import lombok.Getter;
import lombok.NonNull;
import lombok.Setter;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * A {@link Workspace} whose file system and shell live inside a Docker
 * container.
 *
 * <p>Paths presented to the agent are container-internal paths rooted at
 * {@code workspaceRoot}; {@link #resolve(String)} confines them to that root
 * and {@link #bridge()} routes all tool IO and command execution into the
 * container through {@link DockerWorkspaceBridge}. The host only ever talks
 * to the Docker CLI — tool code is completely unaware of the sandbox.</p>
 *
 * <p>Typical usage: start the container with the project directory mounted
 * at {@code workspaceRoot} (e.g. {@code docker run -v
 * D:\work\project:/workspace ...}), then hand this workspace to the agent
 * request.</p>
 */
@Getter
public class DockerWorkspace implements Workspace {

    @Setter
    private String id;
    @Setter
    private String containerId;
    /** Container-internal absolute path of the agent's working directory. */
    @Setter
    private String workspaceRoot = "/workspace";
    /** Lazily (re)built from {@link #containerId}; also survives Jackson round-trips. */
    private transient WorkspaceBridge bridge;

    /** No-args constructor for serialization frameworks (session persistence). */
    public DockerWorkspace() {
    }

    public DockerWorkspace(String id, @NonNull String containerId,@NonNull String workspaceRoot) {
        if (containerId.isBlank()) {
            throw new IllegalArgumentException("containerId must not be blank");
        }
        if (!workspaceRoot.startsWith("/")) {
            throw new IllegalArgumentException("workspaceRoot must be an absolute container path");
        }
        this.id = id;
        this.containerId = containerId;
        // Trim the trailing slash from the SUPPLIED root — do not read the field,
        // which still holds its default value at this point.
        this.workspaceRoot = workspaceRoot.endsWith("/") && workspaceRoot.length() > 1
                ? workspaceRoot.substring(0, workspaceRoot.length() - 1)
                : workspaceRoot;
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public RuntimeEnvironment runtimeEnvironment() {
        // Always describes the container environment; stateless by nature.
        return RuntimeEnvironment.builder()
                .osType(OsType.LINUX)
                .shellType(ShellType.SH)
                .charset(StandardCharsets.UTF_8)
                .build();
    }

    @Override
    public String workDir() {
        return workspaceRoot;
    }

    @Override
    public Path resolve(String path) {
        // Confine every path to the workspace root inside the container.
        Path root = Paths.get(workspaceRoot).normalize();
        Path result;
        if (path == null || path.isEmpty()) {
            result = root;
            // if absolute path
        } else if (path.startsWith("/")) {
            Path absolute = Paths.get(path).normalize();
            result = absolute.startsWith(root) ? absolute : root.resolve(path.substring(1)).normalize();
            // if relative path. append path to root
        } else {
            result = root.resolve(path).normalize();
        }
        if (!canAccess(result, root)) {
            throw new IllegalArgumentException("File path is out of workspace: " + path);
        }
        return result;
    }

    @Override
    public synchronized WorkspaceBridge bridge() {
        if (bridge == null
                || !(bridge instanceof DockerWorkspaceBridge docker)
                || !docker.containerId().equals(containerId)) {
            bridge = new DockerWorkspaceBridge(containerId);
        }
        return bridge;
    }

    /**
     * ensure the workspace root path does not end with a slash
     * @return the workspace root path without a trailing slash
     */
    private String subLastSlash() {
        return workspaceRoot.endsWith("/") ? workspaceRoot.substring(0, workspaceRoot.length() - 1) : workspaceRoot;
    }

    /**
     * check if the path is accessible
     * @param path the path to check
     * @param root the root path
     * @return true if the path is accessible
     */
    private boolean canAccess(Path path, Path root) {
        return path.normalize().startsWith(root);
    }
}
