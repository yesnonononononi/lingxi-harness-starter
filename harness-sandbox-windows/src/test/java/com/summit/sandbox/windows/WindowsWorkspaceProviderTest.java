package com.summit.sandbox.windows;

import com.summit.core.runtime.workspace.ShellType;
import com.summit.core.runtime.workspace.WorkspaceFiles;
import com.summit.core.workspace.WorkspaceSpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledOnOs(OS.WINDOWS)
class WindowsWorkspaceProviderTest {

    @Test
    void specValidatesWorkDirAndEnvironmentKeys() {
        assertThrows(IllegalArgumentException.class, () -> new WindowsWorkspaceSpec(" "));
        assertThrows(IllegalArgumentException.class, () -> new WindowsWorkspaceSpec(
                "C:\\temp", "", Map.of("BAD-KEY", "1"), true));
        WindowsWorkspaceSpec spec = new WindowsWorkspaceSpec("C:\\temp", "s1", Map.of("FOO_1", "bar"), false);
        assertEquals(WindowsWorkspaceProvider.TYPE, spec.provider());
        assertFalse(spec.writable());
    }

    @Test
    void workspaceFilesEnforcesEnclosureAndWritableFlag(@TempDir Path tempRoot) throws Exception {
        Path workspace = Files.createDirectory(tempRoot.resolve("ws"));
        Path outside = Files.createDirectory(tempRoot.resolve("outside"));
        Path outsideFile = outside.resolve("secret.txt");
        Files.writeString(outsideFile, "nope", StandardCharsets.UTF_8);

        WindowsWorkspaceFiles rwFiles = new WindowsWorkspaceFiles(workspace, true);
        Path nested = Path.of("sub/hello.txt");
        rwFiles.writeString(nested, "world", StandardCharsets.UTF_8);
        assertTrue(rwFiles.exists(nested));
        assertEquals("world", rwFiles.readString(nested, StandardCharsets.UTF_8));
        assertEquals(List.of(new WorkspaceFiles.Entry(workspace.resolve("sub").resolve("hello.txt"), false)),
                rwFiles.walk(workspace, null));

        assertThrows(IllegalArgumentException.class, () -> rwFiles.readString(outsideFile, StandardCharsets.UTF_8));
        assertThrows(IllegalArgumentException.class, () -> rwFiles.writeString(Path.of("../outside/x.txt"), "x", StandardCharsets.UTF_8));

        WindowsWorkspaceFiles roFiles = new WindowsWorkspaceFiles(workspace, false);
        assertEquals("world", roFiles.readString(nested, StandardCharsets.UTF_8));
        assertThrows(AccessDeniedException.class, () -> roFiles.writeString(nested, "mutated", StandardCharsets.UTF_8));
        assertThrows(AccessDeniedException.class, () -> roFiles.deleteFile(nested));
        assertThrows(AccessDeniedException.class, () -> roFiles.createDirectories(Path.of("newdir")));
    }

    @Test
    void commandsRejectInvalidArgumentsAndEscapingWorkDir(@TempDir Path tempRoot) throws Exception {
        Path workspace = Files.createDirectory(tempRoot.resolve("ws"));
        Path outside = Files.createDirectory(tempRoot.resolve("outside"));
        WindowsWorkspaceCommands commands = new WindowsWorkspaceCommands(workspace, SandboxMode.READ_ONLY, Map.of());

        assertThrows(IllegalArgumentException.class, () ->
                commands.execute("echo hi", ShellType.CMD, null, StandardCharsets.UTF_8, 0, TimeUnit.SECONDS, 1024));
        assertThrows(IllegalArgumentException.class, () ->
                commands.execute("echo hi", ShellType.CMD, null, StandardCharsets.UTF_8, 5, TimeUnit.SECONDS, -1));
        assertThrows(IllegalArgumentException.class, () ->
                commands.execute("echo hi", ShellType.CMD, outside.toString(), StandardCharsets.UTF_8, 5, TimeUnit.SECONDS, 1024));
    }

    @Test
    void providerRejectsWrongSpecAndFailsClosedWhenUnprovisioned(@TempDir Path tempRoot) throws Exception {
        WindowsWorkspaceProvider provider = new WindowsWorkspaceProvider();
        assertEquals(WindowsWorkspaceProvider.TYPE, provider.type());
        assertThrows(IllegalArgumentException.class, () -> provider.resolve(new WorkspaceSpec() {
            @Override
            public String provider() {
                return "other";
            }

            @Override
            public String workDir() {
                return "C:\\temp";
            }
        }));

        if (!WindowsSandboxProvisioner.isProvisioned()) {
            Path workspace = Files.createDirectory(tempRoot.resolve("ws"));
            assertThrows(IllegalStateException.class, () -> provider.resolve(new WindowsWorkspaceSpec(workspace.toString())));
        }
    }
}
