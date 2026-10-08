package com.summit.sandbox.docker;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Creates (or reuses) sandbox containers.
 *
 * <p>An existing container with the same name is reused as-is; only a
 * framework-managed container created from a <em>different</em> image is
 * removed and recreated, so the sandbox always runs the configured toolchain
 * image. Raw CLI calls go through {@link DockerCli}, path rendering through
 * {@link DockerHostPath} and ownership checks through
 * {@link DockerContainerLabels} and {@link DockerContainers}.</p>
 */
@Slf4j
public final class DockerContainerFactory {

    private DockerContainerFactory() {
    }

    public static String initContainer(String name, String port) {
        return initContainer(name, port, null, null, null);
    }

    /**
     * Ensures a Docker container named {@code name} exists and is running,
     * creating it on demand.
     *
     * <ul>
     *   <li>If a container with the exact same name already exists (running or
     *       stopped), it is started and reused as-is — no second run, no
     *       re-mount. A framework-managed container created from a different
     *       image is removed and recreated instead, so the sandbox always runs
     *       the configured toolchain image (see
     *       {@link DockerContainers#removeOnImageDrift(String, String)}).</li>
     *   <li>Otherwise it is created with:
     *       {@code docker run -d --name <name> [-p <port>:<port>] [-v <hostDir>:<containerDir>] <image>}</li>
     * </ul>
     *
     * @param name         container name (the reuse key); must not be blank
     * @param port         optional port pair published as {@code -p <port>:<port>}; skipped when blank
     * @param hostDir      optional host directory bind-mounted into the container to share project files; skipped when blank
     * @param containerDir in-container mount point for {@code hostDir}; when blank defaults to "/workspace"
     * @param image        container image; when blank defaults to
     *                     {@link DockerSandboxImage#DEFAULT}, the framework's general-purpose
     *                     development image (JDK, Maven, Git, Node.js)
     * @return the container id (short or full id)
     */
    public static String initContainer(String name, String port, String hostDir, String containerDir, String image) {
        return initContainer(name, port, hostDir, containerDir, image, Map.of());
    }

    /**
     * Creates a container carrying {@code labels}; the full label set proves
     * ownership of an existing same-named container.
     */
    public static String initContainer(String name, String port, String hostDir, String containerDir,
                                       String image, Map<String, String> labels) {
        return initContainer(name, port, hostDir, containerDir, image, labels, Set.of());
    }

    /**
     * Creates a container with persistent ownership and recovery metadata.
     *
     * <p>All entries of {@code labels} are written to the container, but only
     * {@code ownershipLabelKeys} are used to recognize a pre-existing container
     * with the same name as owned by the caller. Volatile metadata (creation
     * timestamp and friends) must therefore stay out of that set, otherwise the
     * container could never be reused across restarts.</p>
     *
     * @param ownershipLabelKeys subset of {@code labels} proving ownership; an empty
     *                           set means every label participates in the check
     */
    public static String initContainer(String name, String port, String hostDir, String containerDir,
                                       String image, Map<String, String> labels,
                                       Set<String> ownershipLabelKeys) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("container name must not be blank");
        }
        Map<String, String> ownershipLabels = DockerContainerLabels.ownership(labels, ownershipLabelKeys);
        String requestedImage = DockerSandboxImage.resolve(image);
        String existing = findIdByName(name);
        if (existing != null) {
            Map<String, String> actual = DockerContainerLabels.inspectOrThrow(existing);
            List<String> mismatched = DockerContainerLabels.mismatched(actual, ownershipLabels);
            if (!ownershipLabels.isEmpty() && !mismatched.isEmpty()) {
                throw new IllegalStateException("existing container '" + name + "' (" + existing
                        + ") is not owned by this workspace: " + String.join("; ", mismatched)
                        + ". Remove it (docker rm -f " + existing
                        + ") or configure a different container name.");
            }
            List<String> drifted = DockerContainerLabels.mismatched(actual, labels);
            if (!drifted.isEmpty()) {
                log.debug("reused docker container '{}' has metadata labels that differ "
                        + "from the requested workspace: {}", name, drifted);
            }
            if (DockerContainers.removeOnImageDrift(existing, requestedImage)) {
                existing = null;
            } else {
                // Reuse: make sure it is running; `docker start` is a no-op on a running container.
                DockerContainers.ensureRunning(existing);
                log.info("Reusing existing docker container '{}' ({})", name, existing);
                return existing;
            }
        }

        requireImage(requestedImage);
        String containerId = DockerCli.require(runCommand(name, port, hostDir, containerDir, requestedImage, labels));
        log.info("Started docker container '{}' ({}) from image '{}'", name, containerId, requestedImage);
        return containerId;
    }

    /**
     * Fails fast with an actionable message when {@code image} is not present
     * locally, instead of letting {@code docker run} report a pull failure.
     */
    public static void requireImage(String image) {
        try {
            DockerCli.output(List.of("docker", "image", "inspect", "--format", "{{.Id}}", image));
        } catch (IOException e) {
            throw new IllegalStateException("docker sandbox image '" + image + "' is not available locally."
                    + " Build it with harness-sandbox-docker/src/main/docker/build.ps1 (PowerShell) or build.sh"
                    + " (POSIX), or point the configured container image at an existing one.", e);
        }
    }

    /** Builds the {@code docker run} command for a fresh sandbox container. */
    private static List<String> runCommand(String name, String port, String hostDir, String containerDir,
                                           String image, Map<String, String> labels) {
        List<String> cmd = new ArrayList<>(List.of("docker", "run", "-d", "--name", name));
        labels.forEach((key, value) -> {
            if (value != null && !value.isBlank()) {
                cmd.add("--label");
                cmd.add(key + "=" + value);
            }
        });
        if (port != null && !port.isBlank()) {
            cmd.add("-p");
            cmd.add(port + ":" + port);
        }
        if (hostDir != null && !hostDir.isBlank()) {
            String mountTarget = (containerDir == null || containerDir.isBlank()) ? "/workspace" : containerDir;
            cmd.add("-v");
            cmd.add(DockerHostPath.mountPath(hostDir) + ":" + mountTarget);
        }
        cmd.add(image);
        cmd.add("tail");
        cmd.add("-f");
        cmd.add("/dev/null");
        return cmd;
    }

    /** Returns the id of an existing container with the exact given name, or {@code null}. */
    private static String findIdByName(String name) {
        try {
            String out = DockerCli.trimmedOutput(List.of("docker", "ps", "-a", "-q",
                    "--filter", "name=^/" + name + "$"));
            return out.isEmpty() ? null : out.split("\\R")[0].trim();
        } catch (IOException e) {
            throw new RuntimeException("failed to inspect docker container '" + name + "'", e);
        }
    }
}
