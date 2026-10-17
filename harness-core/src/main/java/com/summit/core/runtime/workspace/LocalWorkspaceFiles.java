package com.summit.core.runtime.workspace;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;

public final class LocalWorkspaceFiles implements WorkspaceFiles {
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

}
