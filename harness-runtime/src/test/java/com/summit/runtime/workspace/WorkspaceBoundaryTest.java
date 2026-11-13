package com.summit.runtime.workspace;

import com.summit.core.exception.OutWorkSpaceException;
import com.summit.core.runtime.workspace.Workspace;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Boundary rules every tool relies on: {@code resolve} is purely geometric, {@code resolveInside}
 * is the one that refuses to leave the workspace.
 */
class WorkspaceBoundaryTest {

    @TempDir
    Path container;

    @Test
    void resolveInsideAcceptsPathsWithinTheWorkspace() throws Exception {
        Workspace workspace = workspace();
        Files.writeString(root().resolve("demo.txt"), "body");

        Path resolved = workspace.resolveInside("demo.txt");

        assertEquals(root().resolve("demo.txt").toRealPath(), resolved.toRealPath());
    }

    @Test
    void resolveInsideAcceptsPathsThatDoNotExistYet() throws Exception {
        Workspace workspace = workspace();

        Path resolved = workspace.resolveInside("later/new.txt");

        // Nothing to resolve on disk, so the geometric result stands and the caller decides what to do.
        assertEquals(root().resolve("later/new.txt").normalize(), resolved);
    }

    @Test
    void resolveInsideRejectsAbsolutePathsOutsideTheWorkspace() throws Exception {
        Workspace workspace = workspace();
        Path outside = container.resolve("outside.txt");
        Files.writeString(outside, "secret");

        assertThrows(OutWorkSpaceException.class, () -> workspace.resolveInside(outside.toString()));
    }

    @Test
    void resolveInsideRejectsParentTraversal() throws Exception {
        Workspace workspace = workspace();

        assertThrows(OutWorkSpaceException.class, () -> workspace.resolveInside("../escaped.txt"));
    }

    @Test
    void resolveInsideRejectsLinksThatPointOutsideTheWorkspace() throws Exception {
        Workspace workspace = workspace();
        Path outsideDir = container.resolve("outside-dir");
        Files.createDirectories(outsideDir);
        Files.writeString(outsideDir.resolve("secret.txt"), "secret");
        Assumptions.assumeTrue(createDirectoryLink(root().resolve("linkdir"), outsideDir),
                "cannot create a directory link in this environment");

        assertThrows(OutWorkSpaceException.class, () -> workspace.resolveInside("linkdir/secret.txt"));
    }

    @Test
    void plainResolveStaysGeometricForCallersThatAllowOutsidePaths() throws Exception {
        Workspace workspace = workspace();
        Path outside = container.resolve("outside.txt");

        // Access modes that intentionally allow outside paths keep using the unguarded resolution.
        assertEquals(outside.toAbsolutePath().normalize(), workspace.resolve(outside.toString()));
    }

    private Workspace workspace() throws Exception {
        Files.createDirectories(root());
        return new LocalWorkspace("boundary-test", root().toString());
    }

    private Path root() {
        return container.resolve("ws");
    }

    /** Symbolic links need privileges on Windows, so fall back to a directory junction. */
    private boolean createDirectoryLink(Path link, Path target) {
        try {
            Files.createSymbolicLink(link, target);
            return true;
        } catch (IOException | UnsupportedOperationException notPermitted) {
            return createJunction(link, target);
        }
    }

    private boolean createJunction(Path link, Path target) {
        try {
            Process process = new ProcessBuilder("cmd", "/c", "mklink", "/J",
                    link.toString(), target.toString()).redirectErrorStream(true).start();
            return process.waitFor() == 0 && Files.exists(link);
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
