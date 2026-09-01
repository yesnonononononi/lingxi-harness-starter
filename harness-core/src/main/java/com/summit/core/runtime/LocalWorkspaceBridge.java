package com.summit.core.runtime;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * The default {@link WorkspaceBridge}: file IO via {@link Files} and command
 * execution via {@link ProcessBuilder}, both directly on the local machine.
 */
public final class LocalWorkspaceBridge implements WorkspaceBridge {

    public static final LocalWorkspaceBridge INSTANCE = new LocalWorkspaceBridge();

    private LocalWorkspaceBridge() {
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
    public void createFile(Path path) throws IOException {
        if (Files.notExists(path)) {
            Files.createFile(path);
        }
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
    public List<String> readLines(Path path, Charset charset) throws IOException {
        return Files.readAllLines(path, charset);
    }

    @Override
    public void writeString(Path path, String content, Charset charset) throws IOException {
        Files.writeString(path, content, charset);
    }

    @Override
    public CommandResult execute(List<String> command, String workDir, Charset charset,
                                 long timeoutSeconds, long maxOutputChars)
            throws IOException, InterruptedException {
        return ProcessRunner.run(command, workDir, charset, timeoutSeconds, maxOutputChars);
    }
}
