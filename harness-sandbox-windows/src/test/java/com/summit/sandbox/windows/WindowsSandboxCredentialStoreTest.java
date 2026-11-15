package com.summit.sandbox.windows;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledOnOs(OS.WINDOWS)
class WindowsSandboxCredentialStoreTest {

    @Test
    void dpapiCredentialRoundTripWorks(@TempDir Path tempRoot) throws Exception {
        Path credFile = tempRoot.resolve("nested").resolve("sandbox-cred.dpapi");
        String generated = WindowsSandboxCredentialStore.generateStrongPassword();
        assertEquals(32, generated.length());

        WindowsSandboxCredentialStore.save(credFile, generated);
        assertTrue(WindowsSandboxCredentialStore.existsAndReadable(credFile));
        assertEquals(generated, WindowsSandboxCredentialStore.load(credFile));

        byte[] raw = Files.readAllBytes(credFile);
        assertNotEquals(generated, new String(raw, StandardCharsets.UTF_8));
    }

    @Test
    void unprovisionedAccountFailsClosed(@TempDir Path tempRoot) throws Exception {
        Path workspace = Files.createDirectory(tempRoot.resolve("workspace"));
        Path missingCred = tempRoot.resolve("missing.dpapi");

        assertFalse(WindowsSandboxProvisioner.isProvisioned("LingXiMissingSandboxUser_999", missingCred));
        assertThrows(IllegalStateException.class, () -> WindowsSandboxRunner.run(
                workspace,
                SandboxMode.READ_ONLY,
                List.of("cmd.exe", "/c", "echo hi"),
                Map.of(),
                Duration.ofSeconds(5),
                StandardCharsets.UTF_8,
                "LingXiMissingSandboxUser_999",
                missingCred
        ));
    }

    @Test
    void aclGrantAndDowngradeAreIdempotentForResolvedAccount(@TempDir Path tempRoot) throws Exception {
        Path workspace = Files.createDirectory(tempRoot.resolve("workspace"));
        String currentUser = System.getProperty("user.name");

        assertTrue(WindowsWorkspaceAcl.ensureWorkspacePermission(workspace, currentUser, SandboxMode.READ_WRITE));
        assertFalse(WindowsWorkspaceAcl.ensureWorkspacePermission(workspace, currentUser, SandboxMode.READ_WRITE));

        assertTrue(WindowsWorkspaceAcl.ensureWorkspacePermission(workspace, currentUser, SandboxMode.READ_ONLY));
        assertFalse(WindowsWorkspaceAcl.ensureWorkspacePermission(workspace, currentUser, SandboxMode.READ_ONLY));
    }
}
