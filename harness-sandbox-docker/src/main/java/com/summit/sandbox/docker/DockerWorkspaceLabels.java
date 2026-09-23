package com.summit.sandbox.docker;

import com.summit.core.workspace.WorkspaceRef;
import com.summit.core.workspace.WorkspaceSpec;
import com.summit.runtime.sandbox.DockerContainers;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The docker labels a managed sandbox container carries.
 *
 * <p>They are the only metadata that survives a container restart, so they both
 * prove ownership of a reused container and let {@link
 * DockerWorkspaceProvider#discoverManagedRecords()} rebuild workspace records
 * after the application store has been lost.</p>
 */
public final class DockerWorkspaceLabels {

    public static final String MANAGED = DockerContainers.MANAGED_LABEL;
    public static final String WORKSPACE_ID = "lingxi.workspace.id";
    public static final String WORKDIR = "lingxi.workspace.workdir";
    public static final String CONTAINER_NAME = "lingxi.workspace.container-name";
    public static final String IMAGE = "lingxi.workspace.image";
    public static final String HOST_DIR = "lingxi.workspace.host-dir";
    public static final String PORT = "lingxi.workspace.port";
    /** Principal the sandbox was created for; empty when the deployment is single-tenant. */
    public static final String SCOPE = "lingxi.workspace.scope";
    public static final String CREATED_AT = "lingxi.workspace.created-at";

    /**
     * Labels that prove an existing same-named container belongs to this
     * workspace. Everything else (notably {@link #CREATED_AT}) is
     * informational metadata: it is re-computed on every provision and cannot
     * be updated on an existing container, so comparing it would make reuse of
     * a container created by an earlier run impossible.
     *
     * <p>{@link #SCOPE} belongs to this set because a container whose principal
     * differs is somebody else's sandbox: reusing it would hand one principal's
     * project directory to another.</p>
     */
    public static final Set<String> OWNERSHIP_KEYS = Set.of(MANAGED, WORKSPACE_ID, SCOPE);

    /** Identity a persisted container label set must carry to be rediscoverable. */
    public record Identity(String workspaceId, String workDir, String containerName) {
    }

    private DockerWorkspaceLabels() {
    }

    /** Builds the full label set written onto a freshly created sandbox container. */
    public static Map<String, String> of(WorkspaceRef ref, WorkspaceSpec spec, String containerName) {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put(MANAGED, Boolean.TRUE.toString());
        labels.put(WORKSPACE_ID, ref.id());
        labels.put(WORKDIR, spec.workDir());
        labels.put(CONTAINER_NAME, containerName);
        labels.put(CREATED_AT, Instant.now().toString());
        put(labels, SCOPE, spec.scope());
        put(labels, IMAGE, spec.configuration().get(DockerWorkspaceProvider.IMAGE));
        put(labels, HOST_DIR, spec.configuration().get(DockerWorkspaceProvider.HOST_DIR));
        put(labels, PORT, spec.configuration().get(DockerWorkspaceProvider.PORT));
        return Map.copyOf(labels);
    }

    /**
     * Rebuilds the provider configuration that was captured in {@code labels}.
     * Only the settings a persisted container can actually report are restored.
     */
    public static Map<String, String> toConfiguration(Map<String, String> labels) {
        Map<String, String> config = new LinkedHashMap<>();
        config.put(DockerWorkspaceProvider.IMAGE, labels.get(IMAGE));
        config.put(DockerWorkspaceProvider.HOST_DIR, labels.get(HOST_DIR));
        config.put(DockerWorkspaceProvider.PORT, labels.get(PORT));
        config.keySet().removeIf(key -> config.get(key) == null || config.get(key).isBlank());
        return config;
    }

    /** Reads back the identity written by {@link #of}, or empty when the labels are incomplete. */
    public static Optional<Identity> identity(Map<String, String> labels) {
        String workspaceId = labels.get(WORKSPACE_ID);
        String workDir = labels.get(WORKDIR);
        String containerName = labels.get(CONTAINER_NAME);
        if (workspaceId == null || workspaceId.isBlank()
                || workDir == null || !workDir.startsWith("/")
                || containerName == null || containerName.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(new Identity(workspaceId, workDir, containerName));
    }

    /** Renders a workspace id as a docker-safe container-name fragment. */
    public static String safeName(String id) {
        return id.toLowerCase().replaceAll("[^a-z0-9_.-]", "-");
    }

    private static void put(Map<String, String> labels, String key, String value) {
        if (value != null && !value.isBlank()) {
            labels.put(key, value);
        }
    }
}
