package com.summit.runtime.sandbox;

import com.summit.core.runtime.ProcessRunner;
import com.summit.core.runtime.workspace.WorkspaceBridge;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Docker-module {@link WorkspaceBridge} that routes file IO and command execution into a
 * Docker container through the {@code docker exec} / {@code docker cp} CLI.
 *
 * <p>All {@link Path} arguments are container-internal absolute paths. The
 * bridge process itself (docker CLI) runs on the host, so its output is
 * always decoded as UTF-8; the workspace charset applies to file contents
 * inside the container.</p>
 *
 * <p>Container creation lives in {@link DockerContainerFactory}, its state in
 * {@link DockerContainers}, label handling in {@link DockerContainerLabels},
 * mount lookup in {@link DockerMounts} and raw CLI calls in {@link DockerCli}.</p>
 */
@Slf4j
public class DockerWorkspaceBridge implements WorkspaceBridge {

    private final String containerId;

    public DockerWorkspaceBridge(String containerId) {
        this.containerId = containerId;
    }

    public String containerId() {
        return containerId;
    }

    @Override
    public boolean exists(Path path) {
        return DockerCli.exitCode(List.of("docker", "exec", containerId, "test", "-e",
                DockerContainerPath.posix(path))) == 0;
    }

    @Override
    public void createDirectories(Path path) throws IOException {
        DockerCli.run(List.of("docker", "exec", containerId, "mkdir", "-p", DockerContainerPath.posix(path)));
    }

    @Override
    public void createFile(Path path) throws IOException {
        String target = DockerContainerPath.posix(path);
        DockerCli.run(List.of("docker", "exec", containerId, "sh", "-c",
                "[ -e '" + target + "' ] || touch '" + target + "'"));
    }

    @Override
    public void deleteFile(Path path) throws IOException {
        if (exists(path)) {
            DockerCli.run(List.of("docker", "exec", containerId, "rm", "-f", DockerContainerPath.posix(path)));
        }
    }

    @Override
    public String readString(Path path, Charset charset) throws IOException {
        byte[] raw = DockerCli.outputBytes(List.of("docker", "exec", containerId, "cat",
                DockerContainerPath.posix(path)));
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
            DockerCli.run(List.of("docker", "cp", temp.toString(),
                    containerId + ":" + DockerContainerPath.posix(path)));
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

        return ProcessRunner.run(full, null, DockerCli.CHARSET, timeoutSeconds, maxOutputChars);
    }
}
