package com.summit.harnessexample;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.summit.core.runtime.Workspace;
import com.summit.runtime.sandbox.DockerWorkspace;
import com.summit.runtime.sandbox.DockerWorkspaceBridge;
import com.summit.runtime.sandbox.DockerWorkspaceBridge.ContainerMount;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Application-level "select a workspace folder" logic for the docker sandbox
 * mode.
 *
 * <p>Choosing a host folder either reuses an existing container that already
 * bind-mounts that folder (discovered via docker metadata — external, historical
 * and the default {@code agent-sandbox} containers included) or creates a new
 * container that mounts it. The new sandbox replaces the active workspace
 * globally (see {@link ActiveWorkspace}) and every connected page is notified
 * through the {@code WORKSPACE_CHANGED} SSE event.</p>
 *
 * <p>In {@code lingxi.agent.workspace=local} mode the selector degrades to a
 * plain local working-directory switch ({@link LocalWorkSpace#updateWorkDir}),
 * because there is no container to create or reuse.</p>
 *
 * <p>Boundary: an agent task that is already running when the workspace is
 * switched keeps its original {@link Workspace} reference until that task
 * finishes; all subsequent requests use the new sandbox.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WorkspaceSandboxService {

    private static final String MODE_DOCKER = "docker";
    private static final String MODE_LOCAL = "local";
    private static final String EVENT_TYPE = "WORKSPACE_CHANGED";

    /** Prefix for containers created by this service, followed by a path-derived hash. */
    private static final String CONTAINER_NAME_PREFIX = "agent-ws-";

    /** The default workspace bean created at startup (docker sandbox or local). */
    private final Workspace defaultWorkspace;
    private final ActiveWorkspace activeWorkspace;
    private final SseEventPublisher sseEventPublisher;
    private final ObjectMapper objectMapper;

    @Value("${lingxi.agent.container-name:agent-sandbox}")
    private String containerName;
    @Value("${lingxi.agent.container-image:alpine}")
    private String containerImage;
    @Value("${lingxi.agent.container-port:}")
    private String containerPort;
    @Value("${lingxi.agent.container-workdir:/workspace}")
    private String containerWorkdir;
    @Value("${lingxi.agent.workspace-dir:}")
    private String configuredWorkspaceDir;

    /** Immutable snapshot of the current workspace selection. */
    private record WorkspaceState(String hostDir, String containerId, String containerName,
                                  String mode, boolean reused) {
    }

    /** Lazily initialized from the startup workspace; replaced on every select. */
    private volatile WorkspaceState state;

    /**
     * Selects a host folder as the agent's workspace. Docker mode reuses an
     * existing container mounting the folder when possible, otherwise a new
     * container is created with that folder bind-mounted.
     *
     * @param folderPath host folder chosen by the user
     * @return DTO describing the resulting workspace
     */
    public Map<String, Object> select(String folderPath) {
        if (folderPath == null || folderPath.isBlank()) {
            throw new IllegalArgumentException("workspace path must not be blank");
        }
        Path requested = Paths.get(folderPath);
        Path absolute = requested.toAbsolutePath().normalize();
        String hostDir = absolute.toString();
        if (!Files.isDirectory(absolute)) {
            throw new IllegalArgumentException("directory does not exist or is not accessible: " + hostDir);
        }

        WorkspaceState current = ensureState();
        WorkspaceState next;
        Workspace active = activeWorkspace.get();
        if (MODE_LOCAL.equals(current.mode()) || !(defaultWorkspace instanceof DockerWorkspace)) {
            if (!(active instanceof LocalWorkSpace local)) {
                throw new IllegalStateException("current workspace type does not support folder switching");
            }
            local.updateWorkDir(hostDir);
            next = new WorkspaceState(hostDir, null, null, MODE_LOCAL, false);
            log.info("workspace switched (local): {}", hostDir);
        } else {
            next = selectDocker(hostDir);
        }

        state = next;
        broadcastWorkspaceChanged(next, activeWorkspace.get().workDir());
        return toDto(next, activeWorkspace.get().workDir());
    }

    /**
     * Returns the current workspace as a DTO, with the container-internal
     * working directory read live from the active workspace (it may have been
     * changed through {@code POST /agent/workdir}).
     */
    public Map<String, Object> current() {
        WorkspaceState current = ensureState();
        return toDto(current, activeWorkspace.get().workDir());
    }

    /** Returns the workspace mode ("docker" or "local") of the startup configuration. */
    public String mode() {
        return ensureState().mode();
    }

    // ------------------------------------------------------------------
    // docker select
    // ------------------------------------------------------------------

    private WorkspaceState selectDocker(String hostDir) {
        Optional<ContainerMount> existing = DockerWorkspaceBridge.findContainerByMount(hostDir);
        if (existing.isPresent()) {
            ContainerMount hit = existing.get();
            DockerWorkspaceBridge.ensureRunning(hit.containerId());
            String destination = mountRoot(hit.mountDestination());
            DockerWorkspace workspace = DockerWorkspace.attach(UUID.randomUUID().toString(),
                    hit.containerId(), destination);
            activeWorkspace.swap(workspace);
            log.info("workspace switched (docker, reused container '{}' {}): host {} -> container {}",
                    hit.containerName(), hit.containerId(), hostDir, destination);
            return new WorkspaceState(hostDir, hit.containerId(), hit.containerName(), MODE_DOCKER, true);
        }

        String name = deterministicContainerName(hostDir);
        DockerWorkspace workspace = DockerWorkspace.newInstance(
                UUID.randomUUID().toString(),
                workdirRoot(),
                name,
                containerPort,
                hostDir,
                containerImage);
        activeWorkspace.swap(workspace);
        log.info("workspace switched (docker, new container '{}' {}): host {} -> container {}",
                name, workspace.getContainerId(), hostDir, workspace.workDir());
        return new WorkspaceState(hostDir, workspace.getContainerId(), name, MODE_DOCKER, false);
    }

    // ------------------------------------------------------------------
    // state + broadcasting + dto
    // ------------------------------------------------------------------

    private WorkspaceState ensureState() {
        WorkspaceState snapshot = state;
        if (snapshot != null) {
            return snapshot;
        }
        synchronized (this) {
            if (state == null) {
                state = initialState();
            }
            return state;
        }
    }

    private WorkspaceState initialState() {
        if (defaultWorkspace instanceof DockerWorkspace docker) {
            String hostDir = configuredWorkspaceDir == null || configuredWorkspaceDir.isBlank()
                    ? System.getProperty("user.dir")
                    : Paths.get(configuredWorkspaceDir).toAbsolutePath().normalize().toString();
            return new WorkspaceState(hostDir, docker.getContainerId(), containerName,
                    MODE_DOCKER, false);
        }
        // local (or any other host workspace): the working directory IS the host folder
        String workDir = defaultWorkspace.workDir();
        return new WorkspaceState(workDir, null, null, MODE_LOCAL, false);
    }

    private void broadcastWorkspaceChanged(WorkspaceState current, String workDir) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("type", EVENT_TYPE);
        event.put("data", toDto(current, workDir));
        try {
            sseEventPublisher.broadcast(objectMapper.writeValueAsString(event));
        } catch (JsonProcessingException e) {
            log.warn("failed to serialize workspace changed event", e);
        }
    }

    private Map<String, Object> toDto(WorkspaceState current, String workDir) {
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("hostDir", current.hostDir());
        dto.put("containerId", current.containerId());
        dto.put("containerName", current.containerName());
        dto.put("workDir", workDir);
        dto.put("mode", current.mode());
        dto.put("reused", current.reused());
        return dto;
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    /** Normalizes an in-container mount destination to a usable workspace root. */
    private static String mountRoot(String destination) {
        if (destination == null || destination.isBlank()) {
            return "/workspace";
        }
        String root = destination.trim();
        while (root.endsWith("/") && root.length() > 1) {
            root = root.substring(0, root.length() - 1);
        }
        return root;
    }

    /** The container-internal working directory configured for new sandboxes. */
    private String workdirRoot() {
        String root = containerWorkdir == null ? "" : containerWorkdir.trim();
        if (!root.startsWith("/")) {
            root = "/" + root;
        }
        return mountRoot(root);
    }

    /**
     * Derives a stable container name from a host folder so that selecting the
     * same folder later finds and reuses the same container.
     */
    static String deterministicContainerName(String hostDir) {
        String normalized = normalize(hostDir);
        if (File.separatorChar == '\\') {
            normalized = normalized.toLowerCase(Locale.ROOT);
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] hash = digest.digest(normalized.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return CONTAINER_NAME_PREFIX + hex.substring(0, 12);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 not available", e);
        }
    }

    private static String normalize(String path) {
        String norm = path.trim().replace('\\', '/');
        while (norm.endsWith("/") && norm.length() > 1) {
            norm = norm.substring(0, norm.length() - 1);
        }
        return norm;
    }
}
