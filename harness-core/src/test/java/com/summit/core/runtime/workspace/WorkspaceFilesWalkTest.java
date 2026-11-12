package com.summit.core.runtime.workspace;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Listing and traversal stay inside the workspace abstraction; the local backend answers {@code list}.
 */
class WorkspaceFilesWalkTest {

    private final LocalWorkspaceFiles files = new LocalWorkspaceFiles();

    @TempDir
    Path root;

    @Test
    void listReportsDirectoriesWithoutFollowingLinks() throws IOException {
        Files.writeString(root.resolve("a.txt"), "a");
        Files.createDirectories(root.resolve("sub"));

        List<WorkspaceFiles.Entry> entries = files.list(root);

        assertEquals(Set.of("a.txt", "sub"),
                entries.stream().map(entry -> entry.path().getFileName().toString()).collect(Collectors.toSet()));
        assertTrue(entries.stream().filter(entry -> entry.path().getFileName().toString().equals("sub"))
                .allMatch(WorkspaceFiles.Entry::directory));
        assertTrue(entries.stream().filter(entry -> entry.path().getFileName().toString().equals("a.txt"))
                .noneMatch(WorkspaceFiles.Entry::directory));
    }

    @Test
    void walkCollectsNestedFilesAndPrunesRejectedDirectories() throws IOException {
        Files.writeString(root.resolve("top.txt"), "top");
        Files.createDirectories(root.resolve("sub/deep"));
        Files.writeString(root.resolve("sub/deep/nested.txt"), "nested");
        Files.createDirectories(root.resolve("skip"));
        Files.writeString(root.resolve("skip/ignored.txt"), "ignored");

        List<String> names = files.walk(root, path -> path.getFileName().toString().equals("skip"))
                .stream().map(entry -> entry.path().getFileName().toString()).sorted().toList();

        assertEquals(List.of("nested.txt", "top.txt"), names,
                "walk must recurse into kept directories and never enter a rejected one");
    }

    @Test
    void walkWithoutAPrunePredicateSeesEverything() throws IOException {
        Files.createDirectories(root.resolve("sub"));
        Files.writeString(root.resolve("sub/nested.txt"), "nested");

        assertEquals(1, files.walk(root, null).size());
    }

    @Test
    void unsupportedBackendsMustFailLoudly() {
        WorkspaceFiles unsupported = new WorkspaceFiles() {
            @Override
            public boolean exists(Path path) {
                return false;
            }

            @Override
            public void createDirectories(Path path) {
            }

            @Override
            public void deleteFile(Path path) {
            }

            @Override
            public String readString(Path path, java.nio.charset.Charset charset) {
                return "";
            }

            @Override
            public byte[] readBytes(Path path) {
                return new byte[0];
            }

            @Override
            public void writeString(Path path, String content, java.nio.charset.Charset charset) {
            }
        };

        // An empty list would be indistinguishable from an empty directory.
        assertThrows(UnsupportedOperationException.class, () -> unsupported.list(root));
        assertThrows(UnsupportedOperationException.class, () -> unsupported.walk(root, null));
    }
}
