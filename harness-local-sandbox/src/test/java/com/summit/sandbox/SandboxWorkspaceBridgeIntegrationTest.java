package com.summit.sandbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.summit.core.runtime.RuntimeEnvironment;
import com.summit.core.runtime.workspace.ShellType;
import com.summit.core.runtime.workspace.OsType;
import com.summit.core.runtime.workspace.WorkspaceBridge.CommandResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.io.TempDirFactory;
import org.junit.jupiter.api.extension.AnnotatedElementContext;
import org.junit.jupiter.api.extension.ExtensionContext;
import com.summit.sandbox.internal.HelperLoader;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Opt-in integration against the supplied standard SRT installation; never provisions components. */
@EnabledIfSystemProperty(named = "sandbox.srt.cli", matches = ".+")
class SandboxWorkspaceBridgeIntegrationTest {
    @TempDir(factory = InstallationDirectoryFactory.class) Path temporary;

    public static class InstallationDirectoryFactory implements TempDirFactory {
        @Override
        public Path createTempDirectory(AnnotatedElementContext element, ExtensionContext context) throws IOException {
            Path parent = Files.createDirectories(Path.of(System.getProperty("sandbox.test.installRoot", "target/sandbox-installations")))
                    .toAbsolutePath().normalize();
            return Files.createTempDirectory(parent, "application-");
        }
    }

    @Test
    @EnabledIfSystemProperty(named = "sandbox.bash.exe", matches = ".+")
    void bundledBashExecutesRawScriptsAndFindsBundledNodeAndGit() throws Exception {
        Path bash = Path.of(System.getProperty("sandbox.bash.exe"));
        Path runtime = Files.createDirectories(temporary.resolve("runtime"));
        Path node = Files.copy(Path.of(System.getProperty("sandbox.node.exe", SandboxWorkspaceBridgeTest.node().toString())),
                runtime.resolve(System.getProperty("os.name").toLowerCase().contains("win") ? "node.exe" : "node"));
        Path root = Files.createDirectories(temporary.resolve("project"));
        SandboxWorkspace workspace = SandboxWorkspaceProvider.builder().applicationDirectory(temporary)
                .nodeExe(node).srtCli(Path.of(System.getProperty("sandbox.srt.cli"))).bashExe(bash).build()
                .resolve(SandboxWorkspaceSpec.of(root.toString(), SandboxPolicy.builder().build()));
        assertEquals(ShellType.BASH, workspace.runtimeEnvironment().shellType());
        CommandResult result = workspace.bridge().execute("value='A B'; printf '%s\\n' \"$value\"; node -p process.execPath; git --exec-path",
                root.toString(), StandardCharsets.UTF_8, 30, TimeUnit.SECONDS, 4096);
        assertTrue(result.isSuccess(), result.output());
        assertTrue(result.output().contains("A B"), result.output());
        assertTrue(result.output().contains(node.toString()), result.output());
        assertTrue(result.output().replace('\\', '/').contains(bash.getParent().getParent().toString().replace('\\', '/')), result.output());
    }

    @Test
    void realSandboxAllowsWorkspaceIoAndRejectsRestrictedIo() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        Path cli = Path.of(System.getProperty("sandbox.srt.cli"));
        boolean windows = System.getProperty("os.name").toLowerCase().contains("win");
        OsType os = windows ? OsType.WINDOWS
                : System.getProperty("os.name").toLowerCase().contains("mac") ? OsType.MACOS : OsType.LINUX;
        // Keep ACL grants inside the fixture, including the supplied executable.
        Path runtime = Files.createDirectories(temporary.resolve("runtime space %BRIDGE_VALUE% & '$ 中文"));
        Path node = Files.copy(SandboxWorkspaceBridgeTest.node(), runtime.resolve(windows ? "node.exe" : "node"));
        Path workspace = Files.createDirectories(temporary.resolve("allowed space & ' $ %BRIDGE_VALUE% 中文"));
        Path restricted = Files.createDirectories(temporary.resolve("restricted"));
        Path canary = restricted.resolve("canary.txt");
        Files.writeString(canary, "HOST-CANARY", StandardCharsets.UTF_8);
        SandboxWorkspaceProvider provider = SandboxWorkspaceProvider.builder().applicationDirectory(temporary)
                .srtCli(cli).nodeExe(node).objectMapper(mapper).timeout(new CommandTimeoutConfig(30, TimeUnit.SECONDS)).build();
        SandboxWorkspace resolved = provider.resolve(SandboxWorkspaceSpec.of(workspace.toString(),
                SandboxPolicy.builder().denyRead(List.of(canary.toString())).denyWrite(List.of(restricted.toString())).build()));
        SandboxWorkspaceBridge bridge = resolved.bridge();
        Path requests = bridge.getRequestDirectory();
        Path helpers = HelperLoader.resolve(temporary, "request.mjs").getParent();

        Path directory = workspace.resolve("nested");
        Path file = directory.resolve("text.txt");
        bridge.createDirectories(directory);
        assertTrue(bridge.exists(directory));
        String content = "\uFEFF中文\r\n\r\nlast\u0000";
        bridge.writeString(file, content, StandardCharsets.UTF_16LE);
        assertArrayEquals(content.getBytes(StandardCharsets.UTF_16LE), Files.readAllBytes(file));
        assertEquals(content, bridge.readString(file, StandardCharsets.UTF_16LE));
        CommandResult command = bridge.execute(windows ? "[Console]::Write('sandbox-ok')" : "printf '%s' sandbox-ok",
                workspace.toString(), StandardCharsets.UTF_8, 30, TimeUnit.SECONDS, 4096);
        assertTrue(command.isSuccess(), command.output());
        assertEquals("sandbox-ok", command.output());
        bridge.deleteFile(file);
        bridge.deleteFile(file);
        assertFalse(bridge.exists(file));
        assertThrows(IOException.class, () -> bridge.readString(file, StandardCharsets.UTF_8));

        Path tamperedRequest = requests.resolve("tamper.txt");
        // Report every boundary failure after verifying ordinary helper operations.
        assertAll("SRT filesystem restrictions",
                () -> assertThrows(IOException.class, () -> bridge.readString(canary, StandardCharsets.UTF_8)),
                () -> assertThrows(IOException.class, () -> bridge.writeString(canary, "CHANGED", StandardCharsets.UTF_8)),
                () -> assertThrows(IOException.class, () -> bridge.deleteFile(canary)),
                () -> assertEquals("HOST-CANARY", Files.readString(canary)),
                () -> assertThrows(IOException.class, () -> bridge.writeString(tamperedRequest, "CHANGED", StandardCharsets.UTF_8)),
                () -> assertFalse(Files.exists(tamperedRequest)),
                () -> assertThrows(IOException.class, () -> bridge.writeString(helpers.resolve("request.mjs"), "CHANGED", StandardCharsets.UTF_8)));
    }

    @Test
    void ineffectiveDirectoryReadPolicyIsRejectedInsteadOfReturningAnUnsafeWorkspace() throws Exception {
        Path runtime = Files.createDirectories(temporary.resolve("runtime"));
        Path node = Files.copy(SandboxWorkspaceBridgeTest.node(), runtime.resolve(
                System.getProperty("os.name").toLowerCase().contains("win") ? "node.exe" : "node"));
        Path root = Files.createDirectories(temporary.resolve("workspace"));
        Path restricted = Files.createDirectories(temporary.resolve("restricted"));
        Path canary = Files.writeString(restricted.resolve("canary.txt"), "HOST-CANARY");
        SandboxWorkspaceProvider provider = SandboxWorkspaceProvider.builder().applicationDirectory(temporary)
                .srtCli(Path.of(System.getProperty("sandbox.srt.cli"))).nodeExe(node).build();
        com.summit.core.workspace.WorkspaceSpec spec = SandboxWorkspaceSpec.of(root.toString(),
                SandboxPolicy.builder()
                        .denyRead(List.of(restricted.toString())).build());
        try {
            SandboxWorkspace workspace = provider.resolve(spec);
            // A backend with working directory denies may return a usable workspace.
            assertThrows(IOException.class, () -> workspace.bridge().readString(canary, StandardCharsets.UTF_8));
        } catch (UncheckedIOException failure) {
            assertTrue(failure.getCause().getMessage().contains("Sandbox policy did not deny access: " + canary),
                    failure.getCause().getMessage());
        }
        assertEquals("HOST-CANARY", Files.readString(canary));
    }
}
