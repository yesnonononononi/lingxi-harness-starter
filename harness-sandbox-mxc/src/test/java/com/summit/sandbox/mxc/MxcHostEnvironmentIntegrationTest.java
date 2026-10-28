package com.summit.sandbox.mxc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.summit.core.runtime.ProcessRunner;
import com.summit.core.runtime.workspace.Workspace;
import com.summit.core.runtime.workspace.WorkspaceCommands;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class MxcHostEnvironmentIntegrationTest {
    @TempDir Path temporary;

    @Test void publicProviderInheritsHostIdentityWithoutWeakeningDenials() throws Exception {
        String node = System.getProperty("mxc.node"), sdk = System.getProperty("mxc.sdk");
        String canary = System.getenv("LINGXI_SANDBOX_TEST_CREDENTIAL");
        assumeTrue(node != null && sdk != null && canary != null, "Configure native resources and a synthetic credential");
        Path project = Files.createDirectories(temporary.resolve("project"));
        Path denied = Files.createDirectories(temporary.resolve("private"));
        Path secret = Files.writeString(denied.resolve("secret.txt"), "denied-canary");
        MxcRuntime runtime = MxcRuntime.nativeHost(Path.of(node), Path.of(sdk), temporary.resolve("helpers"));
        String commandPath = Path.of(node).getParent() + File.pathSeparator + System.getenv("PATH");
        MxcWorkspaceSpec original = new MxcWorkspaceSpec(project.toString(), "test", List.of(), List.of(),
                Map.of("PATH", commandPath, "LINGXI_SANDBOX_TEST_OVERRIDE", "explicit"), true,
                new MxcSecurityPolicy(List.of(denied.toString()), runtime.windows()
                        ? Arrays.stream(File.listRoots()).map(File::getAbsolutePath).toList() : List.of(), true));
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("LINGXI_SANDBOX_TEST_CREDENTIAL", canary);
        expected.put("LINGXI_SANDBOX_TEST_OVERRIDE", "explicit");
        for (String key : runtime.windows() ? List.of("USERPROFILE", "APPDATA", "LOCALAPPDATA", "TEMP") : List.of("HOME")) {
            assertNotNull(System.getenv(key), "Missing host variable " + key);
            expected.put(key, System.getenv(key));
        }
        ObjectMapper json = new ObjectMapper();
        Files.writeString(project.resolve("identity.cjs"), """
                const fs = require('node:fs');
                const expected = %s;
                for (const [key, value] of Object.entries(expected)) {
                    if (process.env[key] !== value) throw new Error('environment mismatch: ' + key);
                }
                let refused = false;
                try { fs.readFileSync(%s); } catch (error) { refused = error.code === 'EACCES' || error.code === 'EPERM'; }
                if (!refused) throw new Error('denied file was accessible');
                console.log('identity-and-denial-ok');
                """.formatted(json.writeValueAsString(expected), json.writeValueAsString(secret.toString())));
        Workspace workspace = new MxcWorkspaceProvider(runtime).resolve(original);
        WorkspaceCommands.CommandResult result = workspace.bridge().commands().execute("node identity.cjs", project.toString(),
                StandardCharsets.UTF_8, 15, TimeUnit.SECONDS, 2048);
        assertEquals(0, result.exitCode(), result.output());
        assertEquals("identity-and-denial-ok", result.output().strip());
        assertEquals(expected.get(runtime.windows() ? "USERPROFILE" : "HOME"),
                workspace.runtimeEnvironment().envs().get(runtime.windows() ? "USERPROFILE" : "HOME"));
        assertFalse(json.writeValueAsString(original).contains(canary));
        if (Boolean.getBoolean("mxc.github.auth")) {
            WorkspaceCommands.CommandResult host = ProcessRunner.run(List.of("gh", "api", "user", "--jq", ".login"),
                    null, StandardCharsets.UTF_8, 20, TimeUnit.SECONDS, 1024);
            assertEquals(0, host.exitCode(), host.output());
            assertFalse(host.output().isBlank());
            WorkspaceCommands.CommandResult sandbox = workspace.bridge().commands().execute("gh api user --jq .login", project.toString(),
                    StandardCharsets.UTF_8, 20, TimeUnit.SECONDS, 1024);
            assertEquals(0, sandbox.exitCode(), sandbox.output());
            assertEquals(host.output().strip(), sandbox.output().strip());
        }
    }
}
