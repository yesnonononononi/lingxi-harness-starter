package com.summit.sandbox.docker;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;

/**
 * Locates existing containers through their bind mounts, so a sandbox that
 * already shares a project directory can be reused instead of recreated.
 */
@Slf4j
public final class DockerMounts {

    /** One line per container: {@code "<name>|<source> => <destination>;..."} per mount. */
    private static final String MOUNT_TEMPLATE =
            "{{.Name}}|{{range .Mounts}}{{.Source}} => {{.Destination}};{{end}}";

    private DockerMounts() {
    }

    /**
     * A container found by {@link #findByHostDirectory(String)}.
     *
     * @param containerId      the container id (short or full)
     * @param containerName    the container name without the leading '/'
     * @param mountDestination in-container absolute path the host directory is mounted at
     */
    public record ContainerMount(String containerId, String containerName, String mountDestination) {
    }

    /**
     * Looks up any existing container (running or stopped) that bind-mounts the
     * given host directory.
     *
     * <p>The directory is matched against {@code docker inspect} mount sources
     * after two-sided path normalization ({@code \} to {@code /}, trailing
     * slashes stripped). On a Windows host the comparison is case-insensitive,
     * because both docker's reported source and the caller-supplied path may
     * differ in drive-letter casing. The first hit is returned together with
     * the in-container mount destination, so the caller can attach the
     * container without re-creating it.</p>
     *
     * @param hostDir the host directory to search for in containers' bind mounts
     * @return the first container mounting {@code hostDir}, or empty when none does
     * @throws RuntimeException when docker cannot be queried
     */
    public static Optional<ContainerMount> findByHostDirectory(String hostDir) {
        String target = comparable(hostDir);
        for (String containerId : containerIds()) {
            Optional<ContainerMount> mount = mountOf(containerId, target);
            if (mount.isPresent()) {
                return mount;
            }
        }
        return Optional.empty();
    }

    private static String comparable(String hostDir) {
        if (hostDir == null || hostDir.isBlank()) {
            throw new IllegalArgumentException("host directory must not be blank");
        }
        String target = DockerHostPath.normalize(Paths.get(hostDir).toAbsolutePath().normalize().toString());
        if (target.isEmpty()) {
            throw new IllegalArgumentException("host directory must not be blank");
        }
        return target;
    }

    private static List<String> containerIds() {
        try {
            String out = DockerCli.trimmedOutput(List.of("docker", "ps", "-a", "-q"));
            return out.isEmpty() ? List.of() : List.of(out.split("\\R"));
        } catch (IOException e) {
            throw new RuntimeException("failed to list docker containers", e);
        }
    }

    private static Optional<ContainerMount> mountOf(String containerId, String target) {
        String inspect;
        try {
            inspect = DockerCli.output(List.of("docker", "inspect", "-f", MOUNT_TEMPLATE, containerId));
        } catch (IOException e) {
            // The container may have been removed concurrently — skip it.
            log.debug("skipping container {} during mount lookup: {}", containerId, e.getMessage());
            return Optional.empty();
        }
        int separator = inspect.indexOf('|');
        if (separator < 0) {
            return Optional.empty();
        }
        String name = inspect.substring(0, separator).trim();
        if (name.startsWith("/")) {
            name = name.substring(1);
        }
        for (String mount : inspect.substring(separator + 1).split(";")) {
            int arrow = mount.indexOf(" => ");
            if (arrow <= 0) {
                continue;
            }
            String source = DockerHostPath.normalize(mount.substring(0, arrow));
            if (source.isEmpty() || !DockerHostPath.same(source, target)) {
                continue;
            }
            return Optional.of(new ContainerMount(containerId, name, mount.substring(arrow + 4).trim()));
        }
        return Optional.empty();
    }
}
