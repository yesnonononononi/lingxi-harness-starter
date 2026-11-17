package com.summit.sandbox.windows;

import com.summit.core.runtime.workspace.WorkspaceFiles;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.NotDirectoryException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The answer to "is this a directory" comes from metadata, and a failed listing says so with the
 * type {@code java.nio.file} defines for it, not with a message a caller has to pattern-match.
 */
@EnabledOnOs(OS.WINDOWS)
class WindowsWorkspaceFilesTest {

    @TempDir
    Path tempRoot;

    @Test
    void isDirectoryAnswersFromMetadataWithoutListing() throws Exception {
        Path workspace = Files.createDirectory(tempRoot.resolve("ws"));
        WindowsWorkspaceFiles files = new WindowsWorkspaceFiles(workspace, SandboxMode.READ_WRITE);
        Path nested = Path.of("sub/hello.txt");
        files.writeString(nested, "world", StandardCharsets.UTF_8);

        assertTrue(files.isDirectory(Path.of("sub")));
        assertFalse(files.isDirectory(nested), "a file is not a directory");
        assertFalse(files.isDirectory(Path.of("missing")), "absence is a false answer, not an error");
    }

    @Test
    void isDirectoryKeepsTheWorkspaceEnclosure() throws Exception {
        Path workspace = Files.createDirectory(tempRoot.resolve("ws"));
        Path outside = Files.createDirectory(tempRoot.resolve("outside"));
        WindowsWorkspaceFiles files = new WindowsWorkspaceFiles(workspace, SandboxMode.READ_WRITE);

        assertThrows(IllegalArgumentException.class, () -> files.isDirectory(outside));
    }

    @Test
    void readOnlyBackendRefusesEveryMutation() throws Exception {
        Path workspace = Files.createDirectory(tempRoot.resolve("ws"));
        WindowsWorkspaceFiles files = new WindowsWorkspaceFiles(workspace, SandboxMode.READ_ONLY);
        Path nested = Path.of("sub/hello.txt");

        assertThrows(AccessDeniedException.class, () -> files.writeString(nested, "world", StandardCharsets.UTF_8));
        assertThrows(AccessDeniedException.class, () -> files.deleteFile(nested));
        assertThrows(AccessDeniedException.class, () -> files.createDirectories(Path.of("newdir")));
    }

    @Test
    void listRejectsAFileWithNotDirectoryException() throws Exception {
        Path workspace = Files.createDirectory(tempRoot.resolve("ws"));
        WindowsWorkspaceFiles files = new WindowsWorkspaceFiles(workspace, SandboxMode.READ_WRITE);
        Path nested = Path.of("sub/hello.txt");
        files.writeString(nested, "world", StandardCharsets.UTF_8);

        assertThrows(NotDirectoryException.class, () -> files.list(nested));
        // An empty directory is the case that matters: a non-empty one would fail the delete anyway,
        // while a wrong type check would silently remove an empty directory as a side effect.
        Path empty = Files.createDirectory(workspace.resolve("empty"));
        assertThrows(IOException.class, () -> files.deleteFile(Path.of("empty")),
                "deleteFile deletes files only, and says so instead of emptying the directory");
        assertTrue(Files.exists(empty), "a rejected directory must survive the call");
        assertEquals(List.of(new WorkspaceFiles.Entry(workspace.resolve("sub").resolve("hello.txt"), false)),
                files.list(Path.of("sub")));
    }
}
