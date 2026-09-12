package com.summit.sandbox.docker;

import com.summit.core.runtime.Workspace;
import com.summit.core.workspace.*;
import com.summit.runtime.sandbox.DockerWorkspace;
import com.summit.runtime.sandbox.DockerWorkspaceBridge;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Docker implementation of the workspace provider SPI.
 */
public final class DockerWorkspaceProvider implements WorkspaceProvider {
    public static final String TYPE = "docker";
    public static final String CONTAINER_ID = "containerId";
    public static final String CONTAINER_NAME = "containerName";
    public static final String IMAGE = "image";
    public static final String HOST_DIR = "hostDir";
    public static final String PORT = "port";
    public static final String REUSE_BY_HOST_DIRECTORY = "reuseByHostDirectory";
    public static final String REUSED = "reused";

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
                DockerWorkspaceBridge.ensureRunning(mount.containerId());
                Map<String, String> state = new LinkedHashMap<>();
                state.put(CONTAINER_ID, mount.containerId());
                state.put(CONTAINER_NAME, mount.containerName());
                state.put(REUSED, Boolean.TRUE.toString());
                WorkspaceSpec effectiveSpec = new BasicWorkspaceSpec(
                        TYPE, mount.mountDestination(), config);
                return new WorkspaceRecord(ref, effectiveSpec, state, ResourceOwnership.SHARED);
            }
        }

        DockerWorkspace workspace = DockerWorkspace.newInstance(
                ref.id(), spec.workDir(), name, config.get(PORT), hostDir, config.get(IMAGE));
        Map<String, String> state = new LinkedHashMap<>();
        state.put(CONTAINER_ID, workspace.getContainerId());
        state.put(CONTAINER_NAME, name);
        state.put(REUSED, Boolean.FALSE.toString());
        return new WorkspaceRecord(ref, spec, state, ResourceOwnership.MANAGED);
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
        if (record.ownership() == ResourceOwnership.ATTACHED) {
            return;
        }
        if (record.ownership() == ResourceOwnership.SHARED) {
            throw new IllegalStateException("shared Docker workspace requires an application cleanup policy");
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

    private static String safeName(String id) {
        return id.toLowerCase().replaceAll("[^a-z0-9_.-]", "-");
    }

    private static void requireSupported(WorkspaceSpec spec) {
        if (!TYPE.equalsIgnoreCase(spec.provider())) {
            throw new IllegalArgumentException("unsupported workspace provider: " + spec.provider());
        }
    }
}
