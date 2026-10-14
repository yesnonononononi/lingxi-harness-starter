package com.summit.sandbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.summit.core.workspace.BasicWorkspaceSpec;
import com.summit.core.workspace.WorkspaceSpec;
import com.summit.core.json.ExecutionJson;
import com.summit.core.runtime.RuntimeEnvironment;
import com.summit.core.runtime.workspace.OsType;
import com.summit.core.runtime.workspace.ShellType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SandboxWorkspaceProviderTest {
    @TempDir Path temporary;
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void windowsRejectsRestrictedWritesBeforePreparingOrExecutingAnything() throws Exception {
        Path cli = fakeCli("throw new Error('must not run');");
        Path root = Files.createDirectories(temporary.resolve("workspace"));
        Path outside = Files.writeString(temporary.resolve("outside.txt"), "HOST-CANARY");
        SandboxWorkspaceProvider provider = SandboxWorkspaceProvider.builder().applicationDirectory(temporary)
                .srtCli(cli).nodeExe(SandboxWorkspaceBridgeTest.node())
                .environment(RuntimeEnvironment.builder().charset(StandardCharsets.UTF_8)
                        .osType(OsType.WINDOWS).shellType(ShellType.POWERSHELL).build()).build();
        for (SandboxPolicy policy : List.of(SandboxPolicy.builder().build(),
                SandboxPolicy.builder().allowWrite(List.of(root.toString())).build(),
                SandboxPolicy.builder().denyWrite(List.of(root.toString())).build())) {
            UnsupportedOperationException failure = assertThrows(UnsupportedOperationException.class,
                    () -> provider.resolve(SandboxWorkspaceSpec.of(root.toString(), policy)));
            assertTrue(failure.getMessage().contains("cannot enforce a filesystem write allowlist"));
        }
        assertEquals("HOST-CANARY", Files.readString(outside));
        assertFalse(Files.exists(temporary.resolve("resources")));
        assertFalse(Files.exists(temporary.resolve("calls.jsonl")));
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void windowsAcceptsOnlyExplicitlyUnrestrictedWritesAndStillProtectsInternalPaths() throws Exception {
        Path cli = fakeCli("process.stdout.write(JSON.stringify({ready: true}));");
        Path root = Files.createDirectories(temporary.resolve("workspace"));
        SandboxWorkspaceProvider provider = SandboxWorkspaceProvider.builder().applicationDirectory(temporary)
                .srtCli(cli).nodeExe(SandboxWorkspaceBridgeTest.node())
                .environment(RuntimeEnvironment.builder().charset(StandardCharsets.UTF_8)
                        .osType(OsType.WINDOWS).shellType(ShellType.POWERSHELL).build()).build();
        SandboxWorkspace workspace = provider.resolve(SandboxWorkspaceSpec.of(root.toString(),
                SandboxPolicy.builder().restrictWrites(false).build()));
        JsonNode settings = mapper.readTree(workspace.bridge().getSettingsFile().toFile());
        assertTrue(hasPath(settings, "denyWrite", workspace.bridge().getSettingsFile().getParent()));
        assertTrue(hasPath(settings, "denyWrite", temporary.resolve("resources/sandbox-helpers")));
        assertEquals(3, Files.readAllLines(temporary.resolve("calls.jsonl")).size());
    }

    @Test
    void environmentMetadataCannotBypassLocalBackendChecks() throws Exception {
        Path cli = fakeCli("throw new Error('must not run');");
        OsType other = System.getProperty("os.name").toLowerCase().contains("win") ? OsType.LINUX : OsType.WINDOWS;
        assertThrows(IllegalArgumentException.class, () -> SandboxWorkspaceProvider.builder()
                .applicationDirectory(temporary).srtCli(cli).nodeExe(SandboxWorkspaceBridgeTest.node())
                .environment(RuntimeEnvironment.builder().charset(StandardCharsets.UTF_8)
                        .osType(other).shellType(ShellType.SH).build()).build());
        assertFalse(Files.exists(temporary.resolve("calls.jsonl")));
    }

    @Test
    void missingWriteRestrictionInSnapshotsKeepsTheSafeDefault() throws Exception {
        assertTrue(SandboxPolicy.builder().build().restrictWrites());
        assertTrue(mapper.readValue("{}", SandboxPolicy.class).restrictWrites());
        SandboxPolicy full = SandboxPolicy.builder().restrictWrites(false).build();
        assertFalse(mapper.readValue(mapper.writeValueAsBytes(full), SandboxPolicy.class).restrictWrites());
    }

    @Test
    void resolvesThroughTheFinalPolicyAndKeepsDifferentPoliciesIndependent() throws Exception {
        Path cli = fakeCli("process.stdout.write(JSON.stringify({ready: true}));");
        Path root = Files.createDirectories(temporary.resolve("workspace with spaces"));
        SandboxWorkspaceProvider provider = provider(cli);
        SandboxPolicy policy = SandboxPolicy.builder().restrictWrites(false).denyRead(List.of("secrets"))
                .denyWrite(List.of("readonly")).allowedDomains(List.of("example.test")).build();
        SandboxWorkspace workspace = provider.resolve(SandboxWorkspaceSpec.of(root.toString(), "one", policy));
        SandboxWorkspaceBridge bridge = workspace.bridge();
        JsonNode settings = mapper.readTree(bridge.getSettingsFile().toFile());
        assertEquals("example.test", settings.path("network").path("allowedDomains").get(0).asText());
        assertTrue(hasPath(settings, "allowWrite", root));
        assertTrue(hasPath(settings, "denyRead", root.resolve("secrets")));
        assertTrue(hasPath(settings, "denyWrite", root.resolve("readonly")));
        assertTrue(hasPath(settings, "denyWrite", bridge.getSettingsFile().getParent()));
        assertTrue(hasPath(settings, "allowRead", bridge.getRequestDirectory()));
        assertSame(bridge, workspace.bridge());
        assertTrue(workspace.runtimeEnvironment().isolated());
        String original = Files.readString(bridge.getSettingsFile());
        SandboxWorkspace second = provider.resolve(SandboxWorkspaceSpec.of(root.toString(), "one",
                SandboxPolicy.builder().restrictWrites(false).build()));
        assertEquals(workspace.id(), second.id());
        assertNotEquals(bridge.getSettingsFile(), second.bridge().getSettingsFile());
        assertEquals(original, Files.readString(bridge.getSettingsFile()));
        SandboxWorkspace otherScope = provider.resolve(SandboxWorkspaceSpec.of(root.toString(), "two", policy));
        assertNotEquals(workspace.id(), otherScope.id());
        List<String> calls = Files.readAllLines(temporary.resolve("calls.jsonl"));
        assertEquals(9, calls.size());
        assertEquals(bridge.getSettingsFile().toString(), mapper.readTree(calls.get(1)).path("settings").asText());
        assertEquals(bridge.getSettingsFile().toString(), mapper.readTree(calls.get(2)).path("settings").asText());
        try (java.util.stream.Stream<Path> requests = Files.list(bridge.getRequestDirectory())) {
            assertEquals(0, requests.count());
        }
    }

    @Test
    void readOnlyWorkspaceDoesNotReceiveTheAutomaticWriteGrant() throws Exception {
        Path cli = fakeCli("process.stdout.write(JSON.stringify({ready: true}));");
        Path root = Files.createDirectories(temporary.resolve("readonly-workspace"));
        SandboxWorkspace workspace = provider(cli).resolve(SandboxWorkspaceSpec.of(root.toString(),
                SandboxPolicy.builder().restrictWrites(false).denyWrite(List.of(root.toString())).build()));
        JsonNode settings = mapper.readTree(workspace.bridge().getSettingsFile().toFile());
        assertFalse(hasPath(settings, "allowWrite", root));
        assertTrue(hasPath(settings, "allowRead", root));
        assertTrue(hasPath(settings, "denyWrite", root));
    }

    @Test
    void failedReadinessNeverReturnsAWorkspaceOrInstallsAnything() throws Exception {
        Path root = Files.createDirectories(temporary.resolve("workspace"));
        Path cli = fakeCli("console.error('SANDBOX_NOT_READY: Sandbox policy did not deny access'); process.exitCode = 1;");
        UncheckedIOException failure = assertThrows(UncheckedIOException.class,
                () -> provider(cli).resolve(SandboxWorkspaceSpec.of(root.toString(), SandboxPolicy.builder().restrictWrites(false).build())));
        assertTrue(failure.getMessage().contains("initialization failed"));
        assertTrue(failure.getCause().getMessage().contains("policy did not deny access"));
        assertFalse(Files.readString(temporary.resolve("calls.jsonl")).contains("windows-install"));
    }

    @Test
    void specsRoundTripWithTheFrameworkSnapshotMapperWithoutSubtypeRegistration() throws Exception {
        WorkspaceSpec original = SandboxWorkspaceSpec.of(temporary.toString(), "tenant",
                SandboxPolicy.builder().denyRead(List.of("secrets")).build());
        ObjectMapper snapshotMapper = ExecutionJson.newObjectMapper();
        WorkspaceSpec restored = snapshotMapper.readValue(snapshotMapper.writeValueAsBytes(original), WorkspaceSpec.class);
        assertEquals(original, restored);
        assertEquals(List.of("secrets"), SandboxWorkspaceSpec.policy(restored).denyRead());
    }

    @Test
    void invalidWorkspacesAreRejectedBeforeExecutingAnything() throws Exception {
        Path cli = fakeCli("throw new Error('must not run');");
        SandboxWorkspaceProvider provider = provider(cli);
        assertThrows(IllegalArgumentException.class,
                () -> provider.resolve(new BasicWorkspaceSpec("local", temporary.toString())));
        assertThrows(UncheckedIOException.class,
                () -> provider.resolve(SandboxWorkspaceSpec.of(temporary.resolve("missing").toString(), null)));
        assertFalse(Files.exists(temporary.resolve("calls.jsonl")));
    }

    private SandboxWorkspaceProvider provider(Path cli) {
        return SandboxWorkspaceProvider.builder().applicationDirectory(temporary).srtCli(cli)
                .nodeExe(SandboxWorkspaceBridgeTest.node()).objectMapper(mapper).build();
    }

    private Path fakeCli(String probe) throws Exception {
        Path cli = temporary.resolve("fake-cli.mjs");
        Files.writeString(cli, """
                import { appendFileSync } from 'node:fs';
                const args = process.argv.slice(2);
                const settings = args.includes('--settings') ? args[args.indexOf('--settings') + 1] : null;
                appendFileSync(%s, JSON.stringify({args, settings}) + '\\n');
                if (args.includes('--version')) console.log('test-version');
                else if (args.includes('whoami')) console.log('test-sandbox');
                else { %s }
                """.formatted(mapper.writeValueAsString(temporary.resolve("calls.jsonl").toString()), probe));
        return cli;
    }

    private boolean hasPath(JsonNode settings, String rule, Path path) {
        for (JsonNode entry : settings.path("filesystem").path(rule)) {
            if (path.toString().equals(entry.path("path").asText()) && entry.path("literal").asBoolean()) return true;
        }
        return false;
    }
}
