package com.summit.sandbox.mxc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.summit.core.runtime.workspace.WorkspaceStdioLaunch;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MxcWorkspaceBridgeTest {
    @TempDir Path directory;

    @Test void reportsMissingRuntimeAsIoFailureOnFirstFileOperation() {
        MxcRuntime runtime = createRuntime();
        MxcWorkspaceBridge bridge = new MxcWorkspaceBridge(runtime, directory.resolve("launcher.mjs"),
                new MxcWorkspaceSpec(directory.toString()));

        assertThrows(UncheckedIOException.class, () -> bridge.files().exists(directory.resolve("file.txt")));
    }

    @Test void preparesStdioWithTheConstructedWorkspacePolicy() throws Exception {
        MxcRuntime runtime = createRuntime();
        MxcWorkspaceSpec spec = new MxcWorkspaceSpec(directory.toString(), "test", List.of(), List.of(),
                Map.of("WORKSPACE_SETTING", "configured"));
        MxcWorkspaceBridge bridge = new MxcWorkspaceBridge(runtime, directory.resolve("launcher.mjs"), spec);
        WorkspaceStdioLaunch launch = bridge.prepareStdio(List.of("node", "server.mjs"),
                Map.of("SESSION_SETTING", "session"));
        Path request;
        try (Stream<Path> requests = Files.list(runtime.helperDirectory().resolve("requests"))) {
            request = requests.filter(path -> path.getFileName().toString().endsWith(".request.json"))
                    .findFirst().orElseThrow();
        }
        try {
            JsonNode payload = new ObjectMapper().readTree(Files.readAllBytes(request));
            assertEquals(runtime.nativePath(spec.workDir()), payload.path("root").asText());
            assertEquals(runtime.sdkDirectory(), payload.path("sdkDirectory").asText());
            assertEquals("configured", payload.path("environment").path("WORKSPACE_SETTING").asText());
            assertEquals("session", payload.path("environment").path("SESSION_SETTING").asText());
            assertEquals("stdio", payload.path("operation").asText());
        } finally {
            // No supervisor was started, so removing the recipe avoids invoking a missing runtime during cleanup.
            Files.deleteIfExists(request);
            launch.close();
        }
    }

    private MxcRuntime createRuntime() {
        return MxcRuntime.nativeHost(directory.resolve("missing-node"), directory.resolve("sdk"),
                directory.resolve("helpers"));
    }
}
