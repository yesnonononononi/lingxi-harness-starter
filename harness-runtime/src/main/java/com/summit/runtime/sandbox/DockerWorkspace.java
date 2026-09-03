package com.summit.runtime.sandbox;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.summit.core.runtime.*;
import lombok.Getter;
import lombok.NonNull;
import lombok.Setter;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;

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
 * <p>Typical usage: {@link #newInstance(String, String, String, String, String)}
 * creates the workspace backed by a container that is started on demand
 * (reused when one with the same name already exists) with the host project
 * directory bind-mounted at the working directory, then hand this workspace
 * to the agent request.</p>
 */
@Getter
public class DockerWorkspace implements Workspace {

    @Setter
    private String id;
    @Setter
    private String containerId;
    /**
     * Container-internal absolute path of the agent's working directory.
     */
    @Setter
    private String workspaceRoot;
    /**
     * Lazily (re)built from {@link #containerId}; also survives Jackson round-trips.
     */
    private transient WorkspaceBridge bridge;


    /**
     * Deserialization support: a persisted {@link ConversationEntity} carries the
     * session workspace (id + containerId + root). The bridge is transient and
     * lazily re-created against the restored container on first tool IO.
     */
    @JsonCreator
    private DockerWorkspace(@JsonProperty("id") String id,
                            @JsonProperty("containerId") @NonNull String containerId,
                            @JsonProperty("workspaceRoot") @NonNull String workspaceRoot) {
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


    public  static  DockerWorkspace newInstance(String id,String workDir,String name,String port) {
        String containerId = DockerWorkspaceBridge.initContainer(name, port);
        return new DockerWorkspace(id, containerId, workDir);
    }

    /**
     * Creates a workspace backed by a Docker container that is created on
     * demand and reused when one with the same {@code name} already exists.
     *
     * @param id          workspace id
     * @param workDir     in-container working directory; also the mount point when {@code hostDir} is set
     * @param name        container name; an existing container with this name is reused as-is
     * @param port        optional port to publish (e.g. "8080"); {@code null} or blank to skip
     * @param hostDir     optional host directory bind-mounted into the container to share project files; {@code null} or blank to skip
     * @param image       container image; defaults to "alpine" when {@code null} or blank
     */
    public  static  DockerWorkspace newInstance(String id, String workDir, String name, String port, String hostDir, String image) {
        String containerId = DockerWorkspaceBridge.initContainer(name, port, hostDir, workDir, image);
        return new DockerWorkspace(id, containerId, workDir);
    }

    /**
     * Convenience overload of
     * {@link #newInstance(String, String, String, String, String, String)}
     * with a random workspace id.
     */
    public  static  DockerWorkspace newInstance(String workDir, String name, String port, String hostDir, String image) {
        return newInstance(UUID.randomUUID().toString(), workDir, name, port, hostDir, image);
    }


    public  static  DockerWorkspace newInstance(String workDir,String name,String port) {
        return newInstance(UUID.randomUUID().toString(),workDir,name,port);
    }


    public  static  DockerWorkspace newInstance(String name,String port) {
        return newInstance("/",name,port);
    }


    public  static  DockerWorkspace newInstance(String name) {
        return newInstance(name,null);
    }


    public  static  DockerWorkspace newInstance() {
       return newInstance(UUID.randomUUID().toString());
    }

    /**
     * Restores a {@link DockerWorkspace} around an already-existing container
     * instead of creating a new one.
     *
     * <p>Unlike the {@code newInstance(...)} factories, this never starts or
     * creates a container. The caller is responsible for the container actually existing
     * (e.g. one created by a previous session whose {@code containerId} was
     * persisted). The container is reached lazily via {@link #bridge()} on first
     * tool IO / command execution, so a stale id only fails at that point.</p>
     *
     * @param id            workspace id
     * @param containerId   id of an existing container to reuse
     * @param workspaceRoot in-container absolute working directory
     * @return a workspace backed by the existing container
     */
    public static DockerWorkspace attach(String id, @NonNull String containerId, @NonNull String workspaceRoot) {
        return new DockerWorkspace(id, containerId, workspaceRoot);
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
     * check if the path is accessible
     *
     * @param path the path to check
     * @param root the root path
     * @return true if the path is accessible
     */
    private boolean canAccess(Path path, Path root) {
        return path.normalize().startsWith(root);
    }
}
