package com.summit.runtime.sandbox;

import com.summit.core.runtime.ProcessRunner;
import com.summit.core.runtime.WorkspaceBridge;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * {@link WorkspaceBridge} that routes file IO and command execution into a
 * Docker container through the {@code docker exec} / {@code docker cp} CLI.
 *
 * <p>All {@link Path} arguments are container-internal absolute paths. The
 * bridge process itself (docker CLI) runs on the host, so its output is
 * always decoded as UTF-8; the workspace charset applies to file contents
 * inside the container.</p>
 */
@Slf4j
public class DockerWorkspaceBridge implements WorkspaceBridge {

    private static final Charset DOCKER_CLI_CHARSET = StandardCharsets.UTF_8;
    private static final long DOCKER_TIMEOUT_SECONDS = 60;

    private final String containerId;

    public DockerWorkspaceBridge(String containerId) {
        this.containerId = containerId;
    }

    public String containerId() {
        return containerId;
    }

    /**
     * Renders a workspace path as a POSIX container path. On a Windows host the
     * resolved {@link Path} contains backslashes, which docker cannot map to
     * container-internal paths.
     */
    private static String containerPath(Path path) {
        return path.toString().replace('\\', '/');
    }

    /**
     * Ensures a Docker container named {@code name} exists and is running,
     * creating it on demand.
     *
     * <ul>
     *   <li>If a container with the exact same name already exists (running or
     *       stopped), it is started and reused as-is — no second run, no
     *       re-mount.</li>
     *   <li>Otherwise it is created with:
     *       {@code docker run -d --name <name> [-p <port>:<port>] [-v <hostDir>:<containerDir>] <image>}</li>
     * </ul>
     *
     * @param name         container name (the reuse key); must not be blank
     * @param port         optional port pair published as {@code -p <port>:<port>}; skipped when blank
     * @param hostDir      optional host directory bind-mounted into the container to share project files; skipped when blank
     * @param containerDir in-container mount point for {@code hostDir}; when blank defaults to "/workspace"
     * @param image        container image; when blank defaults to "alpine"
     * @return the container id (short or full id)
     */
    public static String initContainer(String name, String port, String hostDir, String containerDir, String image) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("container name must not be blank");
        }
        String existing = findExistingContainer(name);
        if (existing != null) {
            // Reuse: make sure it is running; `docker start` is a no-op on a running container.
            runOrThrow(List.of("docker", "start", existing));
            log.info("Reusing existing docker container '{}' ({})", name, existing);
            return existing;
        }

        List<String> cmd = new ArrayList<>(List.of("docker", "run", "-d", "--name", name));
        if (port != null && !port.isBlank()) {
            cmd.add("-p");
            cmd.add(port + ":" + port);
        }
        if (hostDir != null && !hostDir.isBlank()) {
            String mountTarget = (containerDir == null || containerDir.isBlank()) ? "/workspace" : containerDir;
            cmd.add("-v");
            cmd.add(hostMountPath(hostDir) + ":" + mountTarget);
        }
        cmd.add(image == null || image.isBlank() ? "alpine" : image);
        cmd.add("tail");
        cmd.add("-f");
        cmd.add("/dev/null");

        String containerId = runOrThrow(cmd);
        log.info("Started docker container '{}' ({})", name, containerId);
        return containerId;
    }

    public static String initContainer(String name, String port) {
        return initContainer(name, port, null, null, null);
    }

    /**
     * A container found by {@link #findContainerByMount(String)}.
     *
     * @param containerId       the container id (short or full)
     * @param containerName     the container name without the leading '/'
     * @param mountDestination  in-container absolute path the host directory is mounted at
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
     * the in-container mount destination, so the caller can {@link
     * DockerWorkspace#attach} the container without re-creating it.</p>
     *
     * @param hostDir the host directory to search for in containers' bind mounts
     * @return the first container mounting {@code hostDir}, or empty when none does
     * @throws RuntimeException when docker cannot be queried
     */
    public static Optional<ContainerMount> findContainerByMount(String hostDir) {
        if (hostDir == null || hostDir.isBlank()) {
            throw new IllegalArgumentException("host directory must not be blank");
        }
        String target = normalizeHostPath(Paths.get(hostDir).toAbsolutePath().normalize().toString());
        if (target.isEmpty()) {
            throw new IllegalArgumentException("host directory must not be blank");
        }

        List<String> containerIds;
        try {
            byte[] bytes = runForOutput(List.of("docker", "ps", "-a", "-q"));
            String out = new String(bytes, StandardCharsets.UTF_8).trim();
            if (out.isEmpty()) {
                return Optional.empty();
            }
            containerIds = List.of(out.split("\\R"));
        } catch (IOException e) {
            throw new RuntimeException("failed to list docker containers", e);
        }

        for (String containerId : containerIds) {
            String inspect;
            try {
                // One line per container: "<name>|<source> => <destination>;..." per mount.
                byte[] bytes = runForOutput(List.of("docker", "inspect", "-f",
                        "{{.Name}}|{{range .Mounts}}{{.Source}} => {{.Destination}};{{end}}", containerId));
                inspect = new String(bytes, StandardCharsets.UTF_8);
            } catch (IOException e) {
                // The container may have been removed concurrently — skip it.
                log.debug("skipping container {} during mount lookup: {}", containerId, e.getMessage());
                continue;
            }
            int separator = inspect.indexOf('|');
            if (separator < 0) {
                continue;
            }
            String name = inspect.substring(0, separator).trim();
            if (name.startsWith("/")) {
                name = name.substring(1);
            }
            String mounts = inspect.substring(separator + 1);
            for (String mount : mounts.split(";")) {
                int arrow = mount.indexOf(" => ");
                if (arrow <= 0) {
                    continue;
                }
                String source = normalizeHostPath(mount.substring(0, arrow));
                if (source.isEmpty() || !hostPathEquals(source, target)) {
                    continue;
                }
                String destination = mount.substring(arrow + 4).trim();
                return Optional.of(new ContainerMount(containerId, name, destination));
            }
        }
        return Optional.empty();
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
        runOrThrow(List.of("docker", "start", containerId));
    }

    /**
     * Returns the id of an existing container with the exact given name, or {@code null}.
     */
    private static String findExistingContainer(String name) {
        try {
            byte[] bytes = runForOutput(List.of("docker", "ps", "-a", "-q", "--filter", "name=^/" + name + "$"));
            String out = new String(bytes, StandardCharsets.UTF_8).trim();
            return out.isEmpty() ? null : out.split("\\R")[0].trim();
        } catch (IOException e) {
            throw new RuntimeException("failed to inspect docker container '" + name + "'", e);
        }
    }

    /**
     * Normalizes a host directory for a {@code -v} mount: makes it absolute and
     * converts Windows drive paths (D:\work\project) to forward slashes, which
     * the docker CLI accepts on every host OS.
     */
    private static String hostMountPath(String hostDir) {
        Path p = Paths.get(hostDir).toAbsolutePath().normalize();
        return p.toString().replace('\\', '/');
    }

    /**
     * Normalizes a host path for comparison: translates docker-for-Windows
     * internal mount prefixes back to their native drive form, converts
     * backslashes to forward slashes and strips trailing slashes (keeping a
     * single slash for the filesystem root). Both sides of a mount comparison
     * must pass through this method before being compared.
     */
    private static String normalizeHostPath(String path) {
        String norm = translateDockerHostPath(path.trim());
        norm = norm.replace('\\', '/');
        while (norm.endsWith("/") && norm.length() > 1) {
            norm = norm.substring(0, norm.length() - 1);
        }
        return norm;
    }

    /**
     * Docker Desktop (WSL2 / legacy Hyper-V backend) reports bind-mount sources
     * in an internal form such as {@code /run/desktop/mnt/host/d/Code/...} or
     * {@code /host_mnt/d/Code/...}. Those are mapped back to {@code D:/Code/...}
     * so they can be compared with a native Windows path supplied by the user.
     * The {@code /mnt/<drive>} form is only translated on a Windows host, where
     * it cannot be a genuine Linux directory.
     */
    private static String translateDockerHostPath(String path) {
        if (path.startsWith("/run/desktop/mnt/host/")) {
            return toDrivePath(path, "/run/desktop/mnt/host/".length());
        }
        if (path.startsWith("/host_mnt/")) {
            return toDrivePath(path, "/host_mnt/".length());
        }
        if (isWindowsHost() && path.startsWith("/mnt/")) {
            return toDrivePath(path, "/mnt/".length());
        }
        return path;
    }

    /**
     * Converts an internal docker host path whose mount prefix ends just before
     * a drive letter into its native form ({@code /run/desktop/mnt/host/d/...}
     * -&gt; {@code D:/...}). Returns the input unchanged when the character after
     * the prefix is not a drive letter.
     */
    private static String toDrivePath(String path, int driveIndex) {
        if (driveIndex >= path.length()) {
            return path;
        }
        char drive = path.charAt(driveIndex);
        boolean isLetter = (drive >= 'a' && drive <= 'z') || (drive >= 'A' && drive <= 'Z');
        if (!isLetter) {
            return path;
        }
        if (driveIndex + 1 < path.length() && path.charAt(driveIndex + 1) != '/') {
            return path;
        }
        String rest = driveIndex + 1 < path.length() ? path.substring(driveIndex + 1) : "/";
        return Character.toUpperCase(drive) + ":" + rest;
    }

    /**
     * Compares two normalized host paths. On a Windows host the comparison is
     * case-insensitive (docker may report {@code D:\...} while the caller
     * supplies {@code d:/...}); elsewhere it is case-sensitive.
     */
    private static boolean hostPathEquals(String a, String b) {
        return isWindowsHost()
                ? a.toLowerCase(Locale.ROOT).equals(b.toLowerCase(Locale.ROOT))
                : a.equals(b);
    }

    private static boolean isWindowsHost() {
        return File.separatorChar == '\\';
    }

    /**
     * Runs a docker command, throwing {@link RuntimeException} on failure.
     *
     * @return trimmed command output
     */
    private static String runOrThrow(List<String> command) {
        try {
            return new String(runForOutput(command), StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            throw new RuntimeException("docker command failed: " + String.join(" ", command), e);
        }
    }


    @Override
    public boolean exists(Path path) {
        return runForExitCode(List.of("docker", "exec", containerId, "test", "-e", containerPath(path))) == 0;
    }

    @Override
    public void createDirectories(Path path) throws IOException {
        run(List.of("docker", "exec", containerId, "mkdir", "-p", containerPath(path)));
    }

    @Override
    public void createFile(Path path) throws IOException {
        run(List.of("docker", "exec", containerId, "sh", "-c",
                "[ -e '" + containerPath(path) + "' ] || touch '" + containerPath(path) + "'"));
    }

    @Override
    public void deleteFile(Path path) throws IOException {
        if (exists(path)) {
            run(List.of("docker", "exec", containerId, "rm", "-f", containerPath(path)));
        }
    }

    @Override
    public String readString(Path path, Charset charset) throws IOException {
        byte[] raw = runForOutput(List.of("docker", "exec", containerId, "cat", containerPath(path)));
        return new String(raw, charset);
    }

    @Override
    public List<String> readLines(Path path, Charset charset) throws IOException {
        String content = readString(path, charset);
        if (content.isEmpty()) {
            return List.of("");
        }
        return List.of(content.split("\\R", -1));
    }

    @Override
    public void writeString(Path path, String content, Charset charset) throws IOException {
        // Stage the content on the host, then copy it into the container.
        Path temp = Files.createTempFile("lingxi-bridge-", ".tmp");
        try {
            Files.writeString(temp, content, charset);
            run(List.of("docker", "cp", temp.toString(), containerId + ":" + containerPath(path)));
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    /**
     * {@code docker exec -w <workdir> <containerId> <command>} —
     * execute a command in a container
     */
    @Override
    public CommandResult execute(List<String> command, String workDir, Charset charset,
                                 long timeoutSeconds, long maxOutputChars)
            throws IOException, InterruptedException {
        List<String> full = new ArrayList<>();
        full.add("docker");
        full.add("exec");
        if (workDir != null && !workDir.isBlank()) {
            full.add("-w");
            full.add(workDir);
        }
        full.add(containerId);
        full.addAll(command);

        return ProcessRunner.run(full, null, DOCKER_CLI_CHARSET, timeoutSeconds, maxOutputChars);
    }

    /**
     * execute a command and check the exit code
     * @see ProcessRunner
     * @param command [ls, -l]
     */
    private void run(List<String> command) throws IOException {
        try {
            CommandResult result = ProcessRunner.run(command, null, DOCKER_CLI_CHARSET, DOCKER_TIMEOUT_SECONDS, Long.MAX_VALUE);
            if (result.timedOut() || result.exitCode() != 0) {
                throw new IOException("docker command failed (" + result.exitCode() + "): "
                        + String.join(" ", command) + " -> " + result.output());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("docker command interrupted: " + String.join(" ", command), e);
        }
    }

    /**
     * run a command and return its output
     * @param command a command
     * @return output
     */
    private static byte[] runForOutput(List<String> command) throws IOException {
        try {
            CommandResult result = ProcessRunner.run(command, null, DOCKER_CLI_CHARSET, DOCKER_TIMEOUT_SECONDS, Long.MAX_VALUE);
            if (result.timedOut() || result.exitCode() != 0) {
                throw new IOException("docker command failed (" + result.exitCode() + "): "
                        + String.join(" ", command) + " -> " + result.output());
            }
            return result.output().getBytes(DOCKER_CLI_CHARSET);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("docker command interrupted: " + String.join(" ", command), e);
        }
    }

    /**
     * run a command and return its exit code
     * @param command a command
     * @return exit code
     */
    private int runForExitCode(List<String> command) {
        try {
            CommandResult result = ProcessRunner.run(command, null, DOCKER_CLI_CHARSET, DOCKER_TIMEOUT_SECONDS, Long.MAX_VALUE);
            return result.timedOut() ? -1 : result.exitCode();
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return -1;
        }
    }
}
