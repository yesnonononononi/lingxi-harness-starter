package com.summit.sandbox.docker;

import com.summit.core.runtime.workspace.Workspace;
import com.summit.core.workspace.*;
import com.summit.runtime.sandbox.DockerWorkspace;
import com.summit.runtime.sandbox.DockerWorkspaceBridge;
import lombok.extern.slf4j.Slf4j;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.ArrayList;
import java.util.List;
import java.time.Instant;

/**
 * Docker implementation of the workspace provider SPI.
 */
@Slf4j
public final class DockerWorkspaceProvider implements WorkspaceProvider {
    public static final String TYPE = "docker";
    public static final String CONTAINER_ID = "containerId";
    public static final String CONTAINER_NAME = "containerName";
    public static final String IMAGE = "image";
    public static final String HOST_DIR = "hostDir";
    public static final String PORT = "port";
    public static final String REUSE_BY_HOST_DIRECTORY = "reuseByHostDirectory";
    public static final String REUSED = "reused";
    public static final String LABEL_MANAGED = DockerWorkspaceBridge.MANAGED_LABEL;
    public static final String LABEL_WORKSPACE_ID = "lingxi.workspace.id";
    public static final String LABEL_WORKDIR = "lingxi.workspace.workdir";
    public static final String LABEL_CONTAINER_NAME = "lingxi.workspace.container-name";
    public static final String LABEL_IMAGE = "lingxi.workspace.image";
    public static final String LABEL_HOST_DIR = "lingxi.workspace.host-dir";
    public static final String LABEL_PORT = "lingxi.workspace.port";
    public static final String LABEL_CREATED_AT = "lingxi.workspace.created-at";

    /**
     * Labels that prove an existing same-named container belongs to this
     * workspace. Everything else (notably {@link #LABEL_CREATED_AT}) is
     * informational metadata: it is re-computed on every provision and cannot
     * be updated on an existing container, so comparing it would make reuse of
     * a container created by an earlier run impossible.
     */
    public static final Set<String> OWNERSHIP_LABELS = Set.of(LABEL_MANAGED, LABEL_WORKSPACE_ID);

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public WorkspaceRecord provision(WorkspaceRef ref, WorkspaceSpec spec) {
        requireSupported(spec);
        Map<String, String> config = spec.configuration();
        String name = value(config, CONTAINER_NAME, "workspace-" + safeName(ref.id()));
        String hostDir = config.get(HOST_DIR);
        // Reuse any container already mounting this project directory.
        if (hostDir != null && Boolean.parseBoolean(config.getOrDefault(REUSE_BY_HOST_DIRECTORY, "false"))) {
            var existing = DockerWorkspaceBridge.findContainerByMount(hostDir);
            if (existing.isPresent()) {
                var mount = existing.get();
                if (!DockerWorkspaceBridge.removeOnImageDrift(mount.containerId(), config.get(IMAGE))) {
                    DockerWorkspaceBridge.ensureRunning(mount.containerId());
                    Map<String, String> state = new LinkedHashMap<>();
                    state.put(CONTAINER_ID, mount.containerId());
                    state.put(CONTAINER_NAME, mount.containerName());
                    state.put(REUSED, Boolean.TRUE.toString());
                    WorkspaceSpec effectiveSpec = new BasicWorkspaceSpec(
                            TYPE, mount.mountDestination(), config);
                    return new WorkspaceRecord(ref, effectiveSpec, state, ResourceOwnership.SHARED);
                }
                log.info("recreating the sandbox for host directory {} after an image change", hostDir);
            }
        }

        Map<String, String> labels = managementLabels(ref, spec, name);
        String containerId = DockerWorkspaceBridge.initContainer(
                name, config.get(PORT), hostDir, spec.workDir(), config.get(IMAGE), labels, OWNERSHIP_LABELS);
        DockerWorkspace workspace = DockerWorkspace.attach(ref.id(), containerId, spec.workDir());
        Map<String, String> state = new LinkedHashMap<>();
        state.put(CONTAINER_ID, workspace.getContainerId());
        state.put(CONTAINER_NAME, name);
        state.put(REUSED, Boolean.FALSE.toString());
        return new WorkspaceRecord(ref, spec, state, ResourceOwnership.MANAGED);
    }

    /** Reconstructs framework-managed records after the application store has restarted. */
    public List<WorkspaceRecord> discoverManagedRecords() {
        List<WorkspaceRecord> records = new ArrayList<>();
        for (var container : DockerWorkspaceBridge.findManagedContainers(LABEL_MANAGED)) {
            Map<String, String> labels = container.labels();
            String workspaceId = labels.get(LABEL_WORKSPACE_ID);
            String workDir = labels.get(LABEL_WORKDIR);
            String name = labels.get(LABEL_CONTAINER_NAME);
            if (workspaceId == null || workspaceId.isBlank()
                    || workDir == null || !workDir.startsWith("/")
                    || name == null || name.isBlank()) {
                continue;
            }
            Map<String, String> config = new LinkedHashMap<>();
            copyLabel(labels, LABEL_IMAGE, config, IMAGE);
            copyLabel(labels, LABEL_HOST_DIR, config, HOST_DIR);
            copyLabel(labels, LABEL_PORT, config, PORT);
            config.put(CONTAINER_NAME, name);
            config.put(REUSE_BY_HOST_DIRECTORY, Boolean.FALSE.toString());
            Map<String, String> state = Map.of(
                    CONTAINER_ID, container.containerId(),
                    CONTAINER_NAME, name,
                    REUSED, Boolean.TRUE.toString());
            records.add(new WorkspaceRecord(new WorkspaceRef(workspaceId),
                    new BasicWorkspaceSpec(TYPE, workDir, config), state, ResourceOwnership.MANAGED));
        }
        return List.copyOf(records);
    }

    @Override
    public WorkspaceRecord reconcile(WorkspaceRecord record) {
        requireSupported(record.spec());
        String containerId = record.providerState().get(CONTAINER_ID);
        if (containerId == null || containerId.isBlank()) {
            return provisionMissing(record, "container id is missing");
        }
        WorkspaceStatus status = DockerWorkspaceBridge.containerStatus(containerId);
        if (status.state() == WorkspaceStatus.State.MISSING) {
            return provisionMissing(record, status.message());
        }
        if (status.state() == WorkspaceStatus.State.ERROR) {
            throw new IllegalStateException(status.message());
        }
        return record;
    }

    @Override
    public Workspace open(WorkspaceRecord record) {
        requireSupported(record.spec());
        String containerId = requireContainerId(record);
        DockerWorkspaceBridge.ensureRunning(containerId);
        return DockerWorkspace.attach(record.ref().id(), containerId, record.spec().workDir());
    }

    @Override
    public WorkspaceStatus inspect(WorkspaceRecord record) {
        try {
            return DockerWorkspaceBridge.containerStatus(requireContainerId(record));
        } catch (RuntimeException e) {
            return new WorkspaceStatus(WorkspaceStatus.State.ERROR, e.getMessage());
        }
    }

    @Override
    public void destroy(WorkspaceRecord record) {
        if (record.ownership() != ResourceOwnership.MANAGED) {
            return;
        }
        DockerWorkspaceBridge.removeContainer(requireContainerId(record));
    }

    private static String requireContainerId(WorkspaceRecord record) {
        String containerId = record.providerState().get(CONTAINER_ID);
        if (containerId == null || containerId.isBlank()) {
            throw new IllegalStateException("Docker workspace has no containerId: " + record.ref().id());
        }
        return containerId;
    }

    private WorkspaceRecord provisionMissing(WorkspaceRecord record, String reason) {
        if (record.ownership() != ResourceOwnership.MANAGED) {
            throw new IllegalStateException("Docker workspace resource cannot be recreated ("
                    + record.ownership() + "): " + reason);
        }
        return provision(record.ref(), record.spec());
    }

    private static String value(Map<String, String> values, String key, String fallback) {
        String value = values.get(key);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static Map<String, String> managementLabels(WorkspaceRef ref, WorkspaceSpec spec, String name) {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put(LABEL_MANAGED, Boolean.TRUE.toString());
        labels.put(LABEL_WORKSPACE_ID, ref.id());
        labels.put(LABEL_WORKDIR, spec.workDir());
        labels.put(LABEL_CONTAINER_NAME, name);
        labels.put(LABEL_CREATED_AT, Instant.now().toString());
        putLabel(labels, LABEL_IMAGE, spec.configuration().get(IMAGE));
        putLabel(labels, LABEL_HOST_DIR, spec.configuration().get(HOST_DIR));
        putLabel(labels, LABEL_PORT, spec.configuration().get(PORT));
        return Map.copyOf(labels);
    }

    private static void putLabel(Map<String, String> labels, String key, String value) {
        if (value != null && !value.isBlank()) {
            labels.put(key, value);
        }
    }

    private static void copyLabel(Map<String, String> labels, String label,
                                  Map<String, String> config, String configKey) {
        putLabel(config, configKey, labels.get(label));
    }

    private static String safeName(String id) {
        return id.toLowerCase().replaceAll("[^a-z0-9_.-]", "-");
    }

    private static void requireSupported(WorkspaceSpec spec) {
        if (!TYPE.equalsIgnoreCase(spec.provider())) {
            throw new IllegalArgumentException("unsupported workspace provider: " + spec.provider());
        }
    }
}
