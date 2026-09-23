package com.summit.runtime.sandbox;

import com.summit.core.workspace.WorkspaceStatus;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * State and ownership of existing sandbox containers: starting, inspecting,
 * removing and rediscovering them. Creation of new containers lives in
 * {@link DockerContainerFactory}.
 */
@Slf4j
public final class DockerContainers {

    /** Label marking a container as created and owned by this framework. */
    public static final String MANAGED_LABEL = "lingxi.workspace.managed";

    private DockerContainers() {
    }

    /** Container metadata reconstructed from Lingxi management labels. */
    public record ManagedContainer(String containerId, Map<String, String> labels) {
    }

    /**
     * Ensures a container is running, starting it when necessary.
     *
     * <p>{@code docker start} is a no-op for an already-running container, so
     * this method is idempotent.</p>
     *
     * @param containerId the container id to start
     * @throws RuntimeException when the container cannot be started
     */
    public static void ensureRunning(String containerId) {
        if (containerId == null || containerId.isBlank()) {
            throw new IllegalArgumentException("container id must not be blank");
        }
        DockerCli.require(List.of("docker", "start", containerId));
    }

    /** Returns the current Docker resource state without creating or starting it. */
    public static WorkspaceStatus containerStatus(String containerId) {
        if (containerId == null || containerId.isBlank()) {
            return new WorkspaceStatus(WorkspaceStatus.State.MISSING, "container id is blank");
        }
        try {
            String running = DockerCli.trimmedOutput(List.of("docker", "inspect", "-f",
                    "{{.State.Running}}", containerId));
            return "true".equalsIgnoreCase(running)
                    ? WorkspaceStatus.ready()
                    : new WorkspaceStatus(WorkspaceStatus.State.STOPPED, "Docker container is stopped: " + containerId);
        } catch (IOException e) {
            return new WorkspaceStatus(WorkspaceStatus.State.MISSING, "Docker container is unavailable: " + containerId);
        }
    }

    /** Returns the image a container was created from, or {@code null} when unknown. */
    public static String imageOf(String containerId) {
        try {
            String image = DockerCli.trimmedOutput(List.of("docker", "inspect", "-f",
                    "{{.Config.Image}}", containerId));
            return image.isEmpty() ? null : image;
        } catch (IOException e) {
            log.debug("could not read the image of container {}: {}", containerId, e.getMessage());
            return null;
        }
    }

    /** Removes a container. This operation is intentionally explicit. */
    public static void removeContainer(String containerId) {
        if (containerId == null || containerId.isBlank()) {
            throw new IllegalArgumentException("container id must not be blank");
        }
        DockerCli.require(List.of("docker", "rm", "-f", containerId));
    }

    /**
     * Removes a framework-managed container that was created from a different
     * image than {@code requestedImage}.
     *
     * <p>A container keeps the image it was created from, so a sandbox that was
     * once started from a minimal image would keep running without the
     * toolchain the configuration now asks for. Removing it here makes the
     * caller recreate the sandbox from the configured image. Containers the
     * framework does not own are never touched.</p>
     *
     * @return {@code true} when the container was removed and must be recreated
     */
    public static boolean removeOnImageDrift(String containerId, String requestedImage) {
        if (!DockerContainerLabels.has(containerId, MANAGED_LABEL, Boolean.TRUE.toString())) {
            return false;
        }
        String actual = imageOf(containerId);
        if (actual == null || DockerSandboxImage.same(actual, requestedImage)) {
            return false;
        }
        log.warn("sandbox container {} runs image '{}' but '{}' is configured; recreating it",
                containerId, actual, requestedImage);
        removeContainer(containerId);
        return true;
    }

    /** Every container (running or stopped) carrying {@code managedLabel=true}. */
    public static List<ManagedContainer> findManagedContainers(String managedLabel) {
        try {
            String output = DockerCli.trimmedOutput(List.of("docker", "ps", "-a", "-q",
                    "--filter", "label=" + managedLabel + "=true"));
            if (output.isEmpty()) {
                return List.of();
            }
            List<ManagedContainer> containers = new ArrayList<>();
            for (String id : output.split("\\R")) {
                try {
                    containers.add(new ManagedContainer(id.trim(), DockerContainerLabels.inspect(id.trim())));
                } catch (IOException e) {
                    // A container may disappear between `docker ps` and `docker inspect`.
                    log.debug("skipping container {} during label discovery: {}", id, e.getMessage());
                }
            }
            return List.copyOf(containers);
        } catch (IOException e) {
            throw new RuntimeException("failed to discover managed docker containers", e);
        }
    }
}
