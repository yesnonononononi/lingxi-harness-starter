package com.summit.sandbox.windows;

import com.summit.core.runtime.workspace.WorkspaceFiles;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Host-side {@link WorkspaceFiles} implementation enforcing workspace enclosure and {@code writable} mode.
 */
final class WindowsWorkspaceFiles implements WorkspaceFiles {
    private final Path workspaceRoot;
    private final boolean writable;

    WindowsWorkspaceFiles(Path workspaceRoot, boolean writable) {
        this.workspaceRoot = Objects.requireNonNull(workspaceRoot, "workspaceRoot").toAbsolutePath().normalize();
        this.writable = writable;
    }

    @Override
    public boolean exists(Path path) {
        Path resolved = resolveEnclosed(path);
        return Files.exists(resolved, LinkOption.NOFOLLOW_LINKS);
    }

    @Override
    public void createDirectories(Path path) throws IOException {
        requireWritable(path);
        Path resolved = resolveEnclosed(path);
        Files.createDirectories(resolved);
    }

    @Override
    public void deleteFile(Path path) throws IOException {
        requireWritable(path);
        Path resolved = resolveEnclosed(path);
        if (Files.isDirectory(resolved, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Cannot delete directory via deleteFile: " + resolved);
        }
        Files.deleteIfExists(resolved);
    }

    @Override
    public String readString(Path path, Charset charset) throws IOException {
        Objects.requireNonNull(charset, "charset");
        Path resolved = resolveEnclosed(path);
        return Files.readString(resolved, charset);
    }

    @Override
    public byte[] readBytes(Path path) throws IOException {
        Path resolved = resolveEnclosed(path);
        return Files.readAllBytes(resolved);
    }

    @Override
    public List<Entry> list(Path directory) throws IOException {
        Path resolved = resolveEnclosed(directory);
        if (!Files.isDirectory(resolved, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Not a directory: " + resolved);
        }
        List<Entry> entries = new ArrayList<>();
        try (Stream<Path> stream = Files.list(resolved)) {
            stream.forEach(child -> {
                boolean isDir = Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS);
                entries.add(new Entry(child, isDir));
            });
        }
        return entries;
    }

    @Override
    public void writeString(Path path, String content, Charset charset) throws IOException {
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(charset, "charset");
        requireWritable(path);
        Path resolved = resolveEnclosed(path);
        Path parent = resolved.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(resolved, content, charset);
    }

    private void requireWritable(Path path) throws AccessDeniedException {
        if (!writable) {
            throw new AccessDeniedException(String.valueOf(path), null, "Workspace is mounted read-only");
        }
    }

    Path resolveEnclosed(Path path) {
        Objects.requireNonNull(path, "path");
        Path candidate = path.isAbsolute()
                ? path.toAbsolutePath().normalize()
                : workspaceRoot.resolve(path).toAbsolutePath().normalize();
        if (!candidate.startsWith(workspaceRoot)) {
            throw new IllegalArgumentException("Path escapes workspace root: " + path);
        }
        return candidate;
    }
}
