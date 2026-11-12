package com.summit.core.runtime.workspace;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.Predicate;

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
     * Reads the whole file content as raw bytes.
     *
     * <p>{@link #readString} decodes with a charset, so it rejects or corrupts payloads that are not
     * text in that charset. Binary callers (images, archives, media) must use this method: the bytes
     * returned are exactly the stored bytes, with no decoding step in between.</p>
     */
    byte[] readBytes(Path path) throws IOException;

    /**
     * Splits bridge-provided text, preserving blank lines and stripping CR, LF or CRLF terminators.
     */
    default List<String> readLines(Path path, Charset charset) throws IOException {
        return readString(path, charset).lines().toList();
    }

    /**
     * One entry directly under a directory.
     *
     * <p>{@code directory} decides whether {@link #walk} descends into it. Implementations must not
     * follow symbolic links when deciding that flag, so a self-referencing link cannot loop the walk.</p>
     */
    record Entry(Path path, boolean directory) {
    }

    /**
     * Lists the entries directly under the given directory, in the implementation's own order.
     *
     * <p>A backend that cannot enumerate its namespace must throw
     * {@link UnsupportedOperationException} instead of returning an empty list: callers cannot tell an
     * unsupported workspace from an empty directory, and would report "no matches" for a full one.</p>
     */
    default List<Entry> list(Path directory) throws IOException {
        throw new UnsupportedOperationException(
                "Listing is not supported by this workspace files implementation");
    }

    /**
     * Collects the regular files under {@code root} at any depth, {@code root} itself excluded.
     *
     * <p>Directories for which {@code directoryToSkip} returns {@code true} are never entered, which is
     * how callers prune vendor and build output without reading it. Derived from {@link #list} on
     * purpose: traversal stays the single listing rule, so a backend only answers one question about
     * its own namespace.</p>
     */
    default List<Entry> walk(Path root, Predicate<Path> directoryToSkip) throws IOException {
        List<Entry> files = new ArrayList<>();
        Deque<Path> pending = new ArrayDeque<>();
        pending.push(root);
        while (!pending.isEmpty()) {
            for (Entry entry : list(pending.pop())) {
                if (!entry.directory()) {
                    files.add(entry);
                } else if (directoryToSkip == null || !directoryToSkip.test(entry.path())) {
                    pending.push(entry.path());
                }
            }
        }
        return files;
    }

    /**
     * Writes (or fully replaces) the file content. Creates the file when missing.
     */
    void writeString(Path path, String content, Charset charset) throws IOException;

}
