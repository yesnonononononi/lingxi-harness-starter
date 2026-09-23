package com.summit.runtime.sandbox;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.summit.core.runtime.*;
import com.summit.core.runtime.workspace.OsType;
import com.summit.core.runtime.workspace.ShellType;
import com.summit.core.runtime.workspace.Workspace;
import com.summit.core.runtime.workspace.WorkspaceBridge;
import lombok.Getter;
import lombok.NonNull;
import lombok.Setter;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.UUID;

/** Compatibility workspace whose file system and shell live inside a Docker container. */
@Getter
public class DockerWorkspace implements Workspace {

    @Setter
    private String id;
    @Setter
    private String containerId;
    /** Container-internal absolute path of the agent's working directory. */
    @Setter
    private String workspaceRoot;
    /** Lazily (re)built from {@link #containerId}; also survives Jackson round-trips. */
    private transient WorkspaceBridge bridge;


    /** Deserialization support for workspace snapshots; the bridge is recreated lazily. */
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
        String containerId = DockerContainerFactory.initContainer(name, port);
        return new DockerWorkspace(id, containerId, workDir);
    }

    /** Creates a workspace backed by a Docker container that is created on demand and reused when one with the same {@code name} already exists. */
    public  static  DockerWorkspace newInstance(String id, String workDir, String name, String port, String hostDir, String image) {
        String containerId = DockerContainerFactory.initContainer(name, port, hostDir, workDir, image);
        return new DockerWorkspace(id, containerId, workDir);
    }

    /** Convenience overload of {@link #newInstance(String, String, String, String, String, String)} with a random workspace id. */
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

    /** Restores a {@link DockerWorkspace} around an already-existing container instead of creating a new one. */
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
                .isolated(true)
                .build();
    }

    @Override
    public String workDir() {
        return workspaceRoot;
    }

    @Override
    public Path resolve(String path) {
        return DockerContainerPath.resolve(workspaceRoot, path);
    }

    @Override
    public boolean encloses(Path path) {
        return DockerContainerPath.encloses(workspaceRoot, path);
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

}
