package com.summit.core.runtime.workspace;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.List;

/** Paths are resolved by the owning workspace; implementations preserve its IO boundary. */
public interface WorkspaceFiles {

    /**
     * Returns whether the given path exists. Implementations may throw
     * {@link java.io.UncheckedIOException} when their backend cannot determine
     * existence; execution or transport failure must not be reported as absence.
     */
    boolean exists(Path path);

    /**
     * Creates the directory and all non-existing parents.
     */
    void createDirectories(Path path) throws IOException;

    /**
     * Deletes the file (not directories) at the given path; no-op when missing.
     */
    void deleteFile(Path path) throws IOException;

    /**
     * Reads the whole file content as a string.
     */
    String readString(Path path, Charset charset) throws IOException;

    /**
     * Splits bridge-provided text, preserving blank lines and stripping CR, LF or CRLF terminators.
     */
    default List<String> readLines(Path path, Charset charset) throws IOException {
        return readString(path, charset).lines().toList();
    }

    /**
     * Writes (or fully replaces) the file content. Creates the file when missing.
     */
    void writeString(Path path, String content, Charset charset) throws IOException;

}
