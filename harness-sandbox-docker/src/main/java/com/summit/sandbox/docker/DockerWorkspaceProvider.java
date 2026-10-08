package com.summit.sandbox.docker;

import com.summit.core.runtime.workspace.Workspace;
import com.summit.core.workspace.*;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Docker implementation of the workspace provider SPI.
 *
 * <p>Container creation lives in {@link DockerContainerFactory}, its runtime
 * state in {@link DockerContainers}, mount lookup in {@link DockerMounts} and
 * the management labels in {@link DockerWorkspaceLabels}; this class only
 * translates workspace records into those operations.</p>
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
    /** Docker only accepts container names built from these characters. */
    private static final Pattern DOCKER_NAME_PATTERN = Pattern.compile("[a-zA-Z0-9][a-zA-Z0-9_.-]*");

    @Override
    public String type() {
        return TYPE;
    }

    /**
     * A sandbox is identified by the host directory it shares, within the
     * principal it belongs to: two projects mounted at different container paths
     * are still two different sandboxes, while re-opening the same project at a
     * different container path must reuse it. The scope comes first because the
     * same host directory is a different resource for a different principal.
     */
    @Override
    public String identityKey(WorkspaceSpec spec) {
        String hostDir = spec.configuration().get(HOST_DIR);
        String key = hostDir == null || hostDir.isBlank() ? spec.workDir() : hostDir;
        return TYPE + "|" + WorkspaceProvider.scopeOf(spec) + "|" + (key == null ? "" : normalizePath(key));
    }

    @Override
    public WorkspaceRecord provision(WorkspaceRef ref, WorkspaceSpec spec) {
        requireSupported(spec);
        Map<String, String> config = spec.configuration();
        // The container is named after the workspace identity unless the caller
        // supplied one that Docker accepts: business-facing labels are not
        // constrained to [a-zA-Z0-9_.-], and an invalid name aborts docker run.
        String name = containerName(config.get(CONTAINER_NAME), ref);

        Optional<WorkspaceRecord> reused = reuseByHostDirectory(ref, spec);
        if (reused.isPresent()) {
            return reused.get();
        }

        Map<String, String> labels = DockerWorkspaceLabels.of(ref, spec, name);
        String containerId = DockerContainerFactory.initContainer(
                name, config.get(PORT), config.get(HOST_DIR), spec.workDir(), config.get(IMAGE),
                labels, DockerWorkspaceLabels.OWNERSHIP_KEYS);
        return new WorkspaceRecord(ref, spec, state(containerId, name, false), ResourceOwnership.MANAGED);
    }

    /** Reconstructs framework-managed records after the application store has restarted. */
    @Override
    public List<WorkspaceRecord> discoverManagedRecords() {
        List<WorkspaceRecord> records = new ArrayList<>();
        for (var container : DockerContainers.findManagedContainers(DockerWorkspaceLabels.MANAGED)) {
            Optional<DockerWorkspaceLabels.Identity> identity = DockerWorkspaceLabels.identity(container.labels());
            if (identity.isEmpty()) {
                continue;
            }
            DockerWorkspaceLabels.Identity restored = identity.get();
            Map<String, String> config = new LinkedHashMap<>(DockerWorkspaceLabels.toConfiguration(container.labels()));
            config.put(CONTAINER_NAME, restored.containerName());
            config.put(REUSE_BY_HOST_DIRECTORY, Boolean.FALSE.toString());
            // Re-derive the identity from the configuration instead of trusting the
            // label: a container adopted this way must land on the same workspace a
            // fresh request for the same directory would resolve to.
            BasicWorkspaceSpec spec = new BasicWorkspaceSpec(TYPE, restored.workDir(),
                    container.labels().get(DockerWorkspaceLabels.SCOPE), config);
            records.add(new WorkspaceRecord(WorkspaceRef.derived(TYPE, identityKey(spec)),
                    spec,
                    state(container.containerId(), restored.containerName(), true),
                    ResourceOwnership.MANAGED));
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
        WorkspaceStatus status = DockerContainers.containerStatus(containerId);
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
        DockerContainers.ensureRunning(containerId);
        return DockerWorkspace.attach(record.ref().id(), containerId, record.spec().workDir());
    }

    @Override
    public WorkspaceStatus inspect(WorkspaceRecord record) {
        try {
            return DockerContainers.containerStatus(requireContainerId(record));
        } catch (RuntimeException e) {
            return new WorkspaceStatus(WorkspaceStatus.State.ERROR, e.getMessage());
        }
    }

    @Override
    public void destroy(WorkspaceRecord record) {
        if (record.ownership() != ResourceOwnership.MANAGED) {
            return;
        }
        DockerContainers.removeContainer(requireContainerId(record));
    }

    /**
     * Reuses any container that already bind-mounts the configured host
     * directory, so re-opening a project does not spawn a second sandbox.
     *
     * @return the shared record, or empty when a new container has to be created
     */
    private Optional<WorkspaceRecord> reuseByHostDirectory(WorkspaceRef ref, WorkspaceSpec spec) {
        Map<String, String> config = spec.configuration();
        String hostDir = config.get(HOST_DIR);
        if (hostDir == null || !Boolean.parseBoolean(config.getOrDefault(REUSE_BY_HOST_DIRECTORY, "false"))) {
            return Optional.empty();
        }
        Optional<DockerMounts.ContainerMount> existing = DockerMounts.findByHostDirectory(hostDir);
        if (existing.isEmpty()) {
            return Optional.empty();
        }
        DockerMounts.ContainerMount mount = existing.get();
        if (!ownedBy(mount.containerId(), spec)) {
            log.info("not sharing container {} mounted at {}: it belongs to another principal",
                    mount.containerId(), hostDir);
            return Optional.empty();
        }
        if (DockerContainers.removeOnImageDrift(mount.containerId(), config.get(IMAGE))) {
            log.info("recreating the sandbox for host directory {} after an image change", hostDir);
            return Optional.empty();
        }
        DockerContainers.ensureRunning(mount.containerId());
        return Optional.of(new WorkspaceRecord(ref,
                new BasicWorkspaceSpec(TYPE, mount.mountDestination(), spec.scope(), config),
                state(mount.containerId(), mount.containerName(), true),
                ResourceOwnership.SHARED));
    }

    /**
     * Whether a container was created for the same principal as {@code spec}.
     *
     * <p>The mount is not enough: two principals can be handed the same host
     * directory, and sharing the container would give each of them the other's
     * project files. A container without the scope label was not created for a
     * known principal, so it is never shared with a scoped request either —
     * provisioning a second sandbox costs seconds, leaking one costs everything.
     * </p>
     */
    private static boolean ownedBy(String containerId, WorkspaceSpec spec) {
        String requested = WorkspaceProvider.scopeOf(spec);
        String actual = DockerContainerLabels.inspectOrThrow(containerId).get(DockerWorkspaceLabels.SCOPE);
        return requested.equals(actual == null ? "" : actual.trim());
    }

    private static Map<String, String> state(String containerId, String containerName, boolean reused) {
        Map<String, String> state = new LinkedHashMap<>();
        state.put(CONTAINER_ID, containerId);
        state.put(CONTAINER_NAME, containerName);
        state.put(REUSED, Boolean.toString(reused));
        return state;
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

    /** Fallback names are derived from the workspace identity, so one project keeps one container name. */
    private static String containerName(String requested, WorkspaceRef ref) {
        String fallback = "workspace-" + DockerWorkspaceLabels.safeName(ref.id());
        if (requested == null || requested.isBlank()) {
            return fallback;
        }
        String name = requested.trim();
        return DOCKER_NAME_PATTERN.matcher(name).matches() ? name : fallback;
    }

    /** Separator- and slash-insensitive form of a directory, so one project keeps one identity. */
    private static String normalizePath(String path) {
        String value = path.trim().replace('\\', '/');
        while (value.endsWith("/") && value.length() > 1) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    private static void requireSupported(WorkspaceSpec spec) {
        if (!TYPE.equalsIgnoreCase(spec.provider())) {
            throw new IllegalArgumentException("unsupported workspace provider: " + spec.provider());
        }
    }
}
