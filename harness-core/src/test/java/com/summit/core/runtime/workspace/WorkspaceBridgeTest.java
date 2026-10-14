package com.summit.core.runtime.workspace;

import com.summit.core.runtime.RuntimeEnvironment;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkspaceBridgeTest {
    private final LocalWorkspaceBridge localBridge = new LocalWorkspaceBridge(ShellType.CMD);
    @TempDir
    Path temporary;

    @ParameterizedTest
    @MethodSource("shells")
    void workspaceBridgeRunsRawScriptInItsConfiguredShell(ShellType shell) throws Exception {
        Workspace workspace = new Workspace() {
            public String id() { return "raw-script"; }
            public String workDir() { return temporary.toString(); }
            public Path resolve(String path) { return temporary.resolve(path); }
            public RuntimeEnvironment runtimeEnvironment() {
                return RuntimeEnvironment.builder().shellType(shell).build();
            }
        };
        String script = switch (shell) {
            case POWERSHELL, PWSH -> "$value = 'A B'; [Console]::Write($value + '|C D')";
            case CMD -> "for %i in (\"A B\") do @echo %~i";
            default -> "value='A B'; printf '%s' \"$value|C D\"";
        };
        WorkspaceBridge.CommandResult result = workspace.bridge().execute(script, workspace.workDir(),
                StandardCharsets.UTF_8, 10, TimeUnit.SECONDS, 4096);
        assertTrue(result.isSuccess(), result.output());
        assertEquals(shell == ShellType.CMD ? "A B" : "A B|C D", result.output().strip());
    }

    @ParameterizedTest
    @MethodSource("fileContents")
    void defaultLineReadingRetainsLocalLineSemantics(String content) throws IOException {
        Path file = temporary.resolve("lines.txt");
        Files.writeString(file, content, StandardCharsets.UTF_8);

        assertEquals(Files.readAllLines(file, StandardCharsets.UTF_8),
                localBridge.readLines(file, StandardCharsets.UTF_8));
        assertEquals(content, localBridge.readString(file, StandardCharsets.UTF_8));
    }

    @Test
    void defaultLineReadingUsesTheSelectedBridgeWithoutOpeningAHostFile() throws IOException {
        Path remoteFile = temporary.resolve("remote-only.txt");
        AtomicReference<Path> receivedPath = new AtomicReference<>();
        AtomicReference<Charset> receivedCharset = new AtomicReference<>();
        WorkspaceBridge remote = new WorkspaceBridge() {
            public boolean exists(Path path) { throw new UnsupportedOperationException(); }
            public void createDirectories(Path path) { throw new UnsupportedOperationException(); }
            public void deleteFile(Path path) { throw new UnsupportedOperationException(); }
            public void writeString(Path path, String content, Charset charset) { throw new UnsupportedOperationException(); }
            public CommandResult execute(String command, String workDir, Charset charset,
                                         long timeoutSeconds, TimeUnit timeUnit, long maxOutputChars) {
                throw new UnsupportedOperationException();
            }
            public String readString(Path path, Charset charset) {
                receivedPath.set(path);
                receivedCharset.set(charset);
                return "first\r\n\r\nlast";
            }
        };

        assertFalse(Files.exists(remoteFile));
        assertEquals(List.of("first", "", "last"), remote.readLines(remoteFile, StandardCharsets.UTF_16LE));
        assertEquals(remoteFile, receivedPath.get());
        assertSame(StandardCharsets.UTF_16LE, receivedCharset.get());
    }

    private static Stream<String> fileContents() {
        return Stream.of("", "\n", "first", "first\n", "first\r\n\r\nlast\rfinal\n",
                "\uFEFF中文\r\nsecond\r", "\n\n");
    }

    private static Stream<ShellType> shells() {
        return System.getProperty("os.name").toLowerCase().contains("win")
                ? Stream.of(ShellType.POWERSHELL, ShellType.CMD) : Stream.of(ShellType.BASH, ShellType.SH);
    }
}
