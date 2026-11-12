package com.summit.core.runtime.workspace;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

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
    public byte[] readBytes(Path path) throws IOException {
        return Files.readAllBytes(path);
    }

    @Override
    public List<Entry> list(Path directory) throws IOException {
        try (Stream<Path> entries = Files.list(directory)) {
            // NOFOLLOW_LINKS: a link is reported as a file, so walk never descends through one.
            return entries.map(path -> new Entry(path, Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)))
                    .toList();
        }
    }

    @Override
    public void writeString(Path path, String content, Charset charset) throws IOException {
        Files.writeString(path, content, charset);
    }

}
