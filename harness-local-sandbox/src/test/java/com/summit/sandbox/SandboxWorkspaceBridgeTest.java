package com.summit.sandbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.summit.core.runtime.RuntimeEnvironment;
import com.summit.core.runtime.workspace.ShellType;
import com.summit.core.runtime.workspace.OsType;
import com.summit.core.runtime.workspace.WorkspaceBridge.CommandResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class SandboxWorkspaceBridgeTest {
    @TempDir Path temporary;

    @ParameterizedTest
    @MethodSource("fileContents")
    void fileOperationsPreserveBytesAndText(String content) throws Exception {
        SandboxWorkspaceBridge bridge = bridge(windows() ? ShellType.POWERSHELL : ShellType.SH);
        Path directory = temporary.resolve("nested space & ' $ %BRIDGE_VALUE% 中文/child");
        Path file = directory.resolve("content.txt");
        assertFalse(bridge.exists(file));
        bridge.createDirectories(directory);
        assertTrue(bridge.exists(directory));
        for (Charset charset : new Charset[] { StandardCharsets.UTF_8, StandardCharsets.UTF_16LE }) {
            bridge.writeString(file, content, charset);
            assertTrue(bridge.exists(file));
            assertArrayEquals(content.getBytes(charset), Files.readAllBytes(file));
            assertEquals(content, bridge.readString(file, charset));
            assertEquals(Files.readAllLines(file, charset), bridge.readLines(file, charset));
        }
        bridge.deleteFile(file);
        assertFalse(bridge.exists(file));
        bridge.deleteFile(file);
        assertThrows(IOException.class, () -> bridge.deleteFile(directory));
    }

    @Test
    void missingAndMalformedFilesReportIoFailures() throws Exception {
        SandboxWorkspaceBridge bridge = bridge(windows() ? ShellType.POWERSHELL : ShellType.SH);
        IOException missing = assertThrows(IOException.class,
                () -> bridge.readString(temporary.resolve("missing"), StandardCharsets.UTF_8));
        assertTrue(missing.getMessage().contains("ENOENT"), missing.getMessage());
        Path file = temporary.resolve("malformed.txt");
        Files.write(file, new byte[] { (byte) 0xC3, 0x28 });
        assertThrows(IOException.class, () -> bridge.readString(file, StandardCharsets.UTF_8));
        assertThrows(IOException.class, () -> bridge.writeString(file, "\uD800", StandardCharsets.UTF_8));
        assertThrows(IOException.class, () -> bridge.writeString(temporary.resolve("missing-parent/file"),
                "content", StandardCharsets.UTF_8));
    }

    @Test
    void backendFailureCannotBeReportedAsAMissingFileOrEmptyContent() throws Exception {
        Path cli = temporary.resolve("broken-cli.mjs");
        Files.writeString(cli, "console.error('policy rejected'); process.exitCode = 9;");
        SandboxWorkspaceBridge bridge = new SandboxWorkspaceBridge(cli, node(), new ObjectMapper(),
                new CommandTimeoutConfig(10, TimeUnit.SECONDS),
                RuntimeEnvironment.builder().osType(windows() ? OsType.WINDOWS : OsType.LINUX).shellType(ShellType.CMD).build(), null, null, temporary);
        UncheckedIOException failure = assertThrows(UncheckedIOException.class, () -> bridge.exists(temporary));
        assertTrue(failure.getCause().getMessage().contains("policy rejected"));
        assertThrows(IOException.class, () -> bridge.readString(temporary, StandardCharsets.UTF_8));
    }

    @Test
    void malformedResponsesAndInvalidResourcesFailExplicitly() throws Exception {
        Path cli = temporary.resolve("malformed-cli.mjs");
        Files.writeString(cli, "process.stdout.write('{\"bytesBase64\":\"\"} trailing diagnostic');");
        RuntimeEnvironment environment = RuntimeEnvironment.builder().osType(windows() ? OsType.WINDOWS : OsType.LINUX).shellType(ShellType.CMD).build();
        CommandTimeoutConfig timeout = new CommandTimeoutConfig(10, TimeUnit.SECONDS);
        SandboxWorkspaceBridge bridge = new SandboxWorkspaceBridge(cli, node(), new ObjectMapper(), timeout, environment, null, null, temporary);
        assertThrows(IOException.class, () -> bridge.readString(temporary, StandardCharsets.UTF_8));
        assertThrows(UncheckedIOException.class, () -> bridge.exists(temporary));
        assertThrows(IllegalArgumentException.class,
                () -> new SandboxWorkspaceBridge(temporary, node(), new ObjectMapper(), timeout, environment, null, null, temporary));
        assertThrows(IllegalArgumentException.class,
                () -> new SandboxWorkspaceBridge(cli, temporary, new ObjectMapper(), timeout, environment, null, null, temporary));
        assertThrows(IllegalArgumentException.class,
                () -> new SandboxWorkspaceBridge(cli, node(), new ObjectMapper(), timeout, environment,
                        temporary.resolve("missing-policy.json"), null, temporary));
    }

    @Test
    void fileOperationTimeoutIsAnIoFailure() throws Exception {
        Path cli = temporary.resolve("slow-cli.mjs");
        Files.writeString(cli, "await new Promise(resolve => setTimeout(resolve, 10000));");
        SandboxWorkspaceBridge bridge = new SandboxWorkspaceBridge(cli, node(), new ObjectMapper(),
                new CommandTimeoutConfig(100, TimeUnit.MILLISECONDS),
                RuntimeEnvironment.builder().osType(windows() ? OsType.WINDOWS : OsType.LINUX).shellType(ShellType.CMD).build(), null, null, temporary);
        IOException failure = assertThrows(IOException.class,
                () -> bridge.readString(temporary, StandardCharsets.UTF_8));
        assertTrue(failure.getMessage().contains("timedOut=true"), failure.getMessage());
    }

    @Test
    void policyIsPassedUnchangedAndRequestDirectoryIsExplicit() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        Path settings = temporary.resolve("original-policy.json");
        Map<String, Object> policy = Map.of("network", Map.of("allowedDomains", List.of("example.test")),
                "filesystem", Map.of("denyRead", List.of(temporary.resolve("restricted").toString()),
                        "allowWrite", List.of(temporary.resolve("workspace").toString())));
        mapper.writeValue(settings.toFile(), policy);
        String original = Files.readString(settings);
        Path record = temporary.resolve("transport-record.json");
        Path requests = Files.createDirectories(temporary.resolve("configured-request-root"));
        Path cli = temporary.resolve("recording-cli.mjs");
        Files.writeString(cli, """
                import { readFileSync, writeFileSync, readdirSync } from 'node:fs';
                import { join } from 'node:path';
                const policyPath = process.argv[process.argv.indexOf('--settings') + 1];
                const config = JSON.parse(readFileSync(policyPath, 'utf8'));
                const root = %s;
                const payloadPath = join(root, readdirSync(root)[0], 'request.json');
                writeFileSync(%s, JSON.stringify({ policyPath, payloadPath, config }));
                console.error('controlled failure'); process.exitCode = 17;
                """.formatted(mapper.writeValueAsString(requests.toString()), mapper.writeValueAsString(record.toString())));
        SandboxWorkspaceBridge bridge = new SandboxWorkspaceBridge(cli, node(), mapper,
                new CommandTimeoutConfig(10, TimeUnit.SECONDS),
                RuntimeEnvironment.builder().osType(windows() ? OsType.WINDOWS : OsType.LINUX).shellType(ShellType.CMD).build(), settings, requests, temporary);
        assertThrows(IOException.class, () -> bridge.readString(temporary, StandardCharsets.UTF_8));
        JsonNode recorded = mapper.readTree(record.toFile());
        assertEquals(mapper.valueToTree(policy), recorded.path("config"));
        assertEquals(settings.toString(), recorded.path("policyPath").asText());
        Path request = Path.of(recorded.path("payloadPath").asText());
        assertEquals(requests, request.getParent().getParent());
        assertFalse(Files.exists(request));
        assertFalse(Files.exists(request.getParent()));
        assertEquals(original, Files.readString(settings));
    }

    @Test
    void interruptionPreservesTheFlagAndRemovesTransportFiles() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        Path marker = temporary.resolve("interruption-marker.json");
        Path requests = Files.createDirectories(temporary.resolve("interruption-requests"));
        Path cli = temporary.resolve("waiting-cli.mjs");
        Files.writeString(cli, """
                import { writeFileSync, readdirSync } from 'node:fs';
                import { join } from 'node:path';
                if (process.argv.includes('--settings')) throw new Error('SRT must own default policy lookup');
                const root = %s;
                const request = join(root, readdirSync(root)[0], 'request.json');
                writeFileSync(%s, JSON.stringify({ request }));
                await new Promise(resolve => setTimeout(resolve, 10000));
                """.formatted(mapper.writeValueAsString(requests.toString()), mapper.writeValueAsString(marker.toString())));
        SandboxWorkspaceBridge bridge = new SandboxWorkspaceBridge(cli, node(), mapper,
                new CommandTimeoutConfig(15, TimeUnit.SECONDS),
                RuntimeEnvironment.builder().osType(windows() ? OsType.WINDOWS : OsType.LINUX).shellType(ShellType.CMD).build(), null, requests, temporary);
        AtomicReference<IOException> failure = new AtomicReference<>();
        AtomicBoolean interrupted = new AtomicBoolean();
        CountDownLatch finished = new CountDownLatch(1);
        Thread worker = Thread.ofPlatform().start(() -> {
            try { bridge.readString(temporary, StandardCharsets.UTF_8); }
            catch (IOException e) { failure.set(e); interrupted.set(Thread.currentThread().isInterrupted()); }
            finally { finished.countDown(); }
        });
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!Files.exists(marker) && System.nanoTime() < deadline) Thread.sleep(20);
            assertTrue(Files.exists(marker), "probe must have started before interruption");
            worker.interrupt();
            assertTrue(finished.await(5, TimeUnit.SECONDS));
            assertNotNull(failure.get());
            assertTrue(interrupted.get());
            JsonNode recorded = mapper.readTree(marker.toFile());
            assertFalse(Files.exists(Path.of(recorded.path("request").asText()).getParent()));
        } finally {
            worker.interrupt();
            worker.join(5000);
        }
    }

    @Test
    void workspaceResolutionAndConfiguredPolicyReachTheSelectedBridge() throws Exception {
        Path settings = temporary.resolve("policy with spaces.json");
        Files.writeString(settings, "{}");
        SandboxWorkspaceBridge bridge = bridge(windows() ? ShellType.POWERSHELL : ShellType.SH);
        SandboxWorkspace workspace = SandboxWorkspace.builder().id("sandbox")
                .workDir(temporary.toString()).runtimeEnvironment(bridge.getRuntimeEnvironment())
                .srtCli(bridge.getSrtCli()).nodeExe(node()).objectMapper(new ObjectMapper())
                .timeout(new CommandTimeoutConfig(10, TimeUnit.SECONDS)).settingsFile(settings)
                .applicationDirectory(temporary).build();
        assertEquals(temporary, workspace.resolve(null));
        assertEquals(temporary, workspace.resolve(""));
        assertEquals(temporary.resolve("b"), workspace.resolve("a/../b"));
        assertEquals(temporary.getParent(), workspace.resolve(".."));
        assertFalse(workspace.encloses(workspace.resolve("..")));
        assertEquals(temporary.resolve("absolute"), workspace.resolve(temporary.resolve("absolute").toString()));
        Path file = workspace.resolve("file.txt");
        workspace.bridge().writeString(file, "policy-check", StandardCharsets.UTF_8);
        assertEquals("policy-check", workspace.bridge().readString(file, StandardCharsets.UTF_8));
        assertEquals(settings, ((SandboxWorkspaceBridge) workspace.bridge()).getSettingsFile());
    }

    @Test
    void rawScriptCrossesTheCliBoundaryWithoutFlatteningOrDoubleWrapping() throws Exception {
        ShellType shell = windows() ? ShellType.POWERSHELL : ShellType.SH;
        String script = windows()
                ? "$value = 'A B'; [Console]::Write($value + '|C D'); exit 7"
                : "value='A B'; printf '%s' \"$value|C D\"; exit 7";
        CommandResult result = bridge(shell).execute(script, temporary.toString(), StandardCharsets.UTF_8,
                10, TimeUnit.SECONDS, 4096);
        assertEquals(7, result.exitCode(), result.output());
        assertEquals("A B|C D", result.output());
        assertFalse(result.timedOut());
    }

    @Test
    void scriptRetainsTheWorkingDirectoryAndOutputBudget() throws Exception {
        ShellType shell = windows() ? ShellType.POWERSHELL : ShellType.SH;
        String script = windows() ? "[Console]::Write([Environment]::CurrentDirectory)" : "printf '%s' \"$PWD\"";
        CommandResult result = bridge(shell).execute(script, temporary.toString(), StandardCharsets.UTF_8,
                10, TimeUnit.SECONDS, 4);
        assertTrue(result.isSuccess(), result.output());
        assertEquals(temporary.toString().substring(0, 4), result.output());
        assertTrue(result.truncated());
    }

    @Test
    void cmdPreservesQuotedExecutableAndScriptArguments() throws Exception {
        if (!windows()) return;
        Path node = node();
        String script = "\"" + node + "\" -e \"process.stdout.write('A B')\"";
        CommandResult result = bridge(ShellType.CMD).execute(script, temporary.toString(),
                StandardCharsets.UTF_8, 10, TimeUnit.SECONDS, 4096);
        assertTrue(result.isSuccess(), result.output());
        assertEquals("A B", result.output());
    }

    private SandboxWorkspaceBridge bridge(ShellType shell) throws Exception {
        Path cli = temporary.resolve("fake cli with spaces.mjs");
        // This fixture replaces sandbox policy setup, while exercising the real
        // bridge, JSON transport, extracted helper, workspace shell, and runner.
        Files.writeString(cli, """
                import { spawn } from 'node:child_process';
                import { readFileSync } from 'node:fs';
                const args = process.argv.slice(2);
                if (args[0] === '--settings') {
                    JSON.parse(readFileSync(args[1], 'utf8'));
                    args.splice(0, 2);
                }
                if (args.length !== 2 || args[0] !== '-c') throw new Error('Helper command must be one CLI argument');
                const windows = process.platform === 'win32';
                const child = spawn(windows ? 'cmd.exe' : '/bin/sh',
                    windows ? ['/d', '/s', '/c', '"' + args[1] + '"'] : ['-c', args[1]],
                    { shell: false, windowsVerbatimArguments: windows, stdio: 'inherit' });
                child.on('error', error => { console.error(error); process.exitCode = 1; });
                child.on('exit', code => { process.exitCode = code ?? 1; });
                """);
        return new SandboxWorkspaceBridge(cli, node(), new ObjectMapper(),
                new CommandTimeoutConfig(10, TimeUnit.SECONDS),
                RuntimeEnvironment.builder().osType(windows() ? OsType.WINDOWS : OsType.LINUX).shellType(shell).build(), null, null, temporary);
    }

    static Path node() {
        for (String directory : System.getenv("PATH").split(File.pathSeparator)) {
            Path candidate = Path.of(directory, windows() ? "node.exe" : "node");
            if (Files.isRegularFile(candidate)) return candidate;
        }
        throw new IllegalStateException("Node is required for sandbox transport tests");
    }

    private static boolean windows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }

    private static Stream<String> fileContents() {
        return Stream.of("", "first", "\r\n\r\n", "\uFEFF中文\r\n\r\nlast\rfinal\n\u0000");
    }
}
