package com.summit.core.runtime.workspace;

import com.summit.core.runtime.ProcessRunner;
import com.summit.core.runtime.RuntimeEnvironment;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * The default {@link WorkspaceBridge}: file IO via {@link Files} and command
 * execution via {@link ProcessBuilder}, both directly on the local machine.
 */
public final class LocalWorkspaceBridge implements WorkspaceBridge {

    private final RuntimeEnvironment environment;

    /** Binds command execution to the shell advertised by the owning workspace. */
    public LocalWorkspaceBridge(ShellType shellType) {
        this(RuntimeEnvironment.builder().shellType(shellType).build());
    }

    public LocalWorkspaceBridge(RuntimeEnvironment environment) {
        this.environment = Objects.requireNonNull(environment, "workspace environment");
        Objects.requireNonNull(environment.shellType(), "workspace shellType must not be null");
    }

    @Override
    public boolean exists(Path path) {
        return Files.exists(path);
    }

    @Override
    public void createDirectories(Path path) throws IOException {
        Files.createDirectories(path);
    }

    @Override
    public void deleteFile(Path path) throws IOException {
        Files.deleteIfExists(path);
    }

    @Override
    public String readString(Path path, Charset charset) throws IOException {
        return Files.readString(path, charset);
    }

    @Override
    public void writeString(Path path, String content, Charset charset) throws IOException {
        Files.writeString(path, content, charset);
    }

    @Override
    public CommandResult execute(String command, String workDir, Charset charset,
                                 long timeoutSeconds, TimeUnit timeUnit, long maxOutputChars)
            throws IOException, InterruptedException {
        return ProcessRunner.run(environment.buildCommand(Objects.requireNonNull(command, "command must not be null")),
                workDir, charset, timeoutSeconds, timeUnit, maxOutputChars);
    }
}
