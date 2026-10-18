package com.summit.sandbox.mxc;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MxcHostEnvironmentTest {
    @TempDir Path temporary;

    @Test void windowsPreservesHostContextAndCaseInsensitiveOverrides() {
        Map<String, String> configured = Map.of("PATH", "bundled", "gh_token", "explicit");
        Map<String, String> environment = MxcHostEnvironment.resolve(Map.of("Path", "host-path", "GH_TOKEN", "host-token",
                "GITHUB_TOKEN", "inherited-token", "USERPROFILE", "C:\\Users\\host", "APPDATA", "C:\\Roaming",
                "LOCALAPPDATA", "C:\\Local", "DOCKER_CONFIG", "C:\\docker", "ProgramFiles(x86)", "C:\\Programs", "=C:", "C:\\"), configured, true);
        assertEquals("bundled", environment.get("PATH"));
        assertFalse(environment.containsKey("Path"));
        assertEquals("explicit", environment.get("GH_TOKEN"));
        assertEquals("inherited-token", environment.get("GITHUB_TOKEN"));
        assertEquals("C:\\Users\\host", environment.get("USERPROFILE"));
        assertEquals("C:\\Users\\host", environment.get("HOME"));
        assertEquals("C:\\Roaming", environment.get("APPDATA"));
        assertEquals("C:\\Local", environment.get("LOCALAPPDATA"));
        assertEquals("C:\\docker", environment.get("DOCKER_CONFIG"));
        assertEquals("C:\\Programs", environment.get("PROGRAMFILES(X86)"));
        assertFalse(environment.containsKey("=C:"));
        assertEquals(Map.of("PATH", "bundled", "gh_token", "explicit"), configured);
    }

    @Test void linuxPreservesCaseAndExplicitHome() {
        Map<String, String> environment = MxcHostEnvironment.resolve(Map.of("HOME", "/home/host", "PATH", "/usr/bin",
                "SSH_AUTH_SOCK", "/run/agent"), Map.of("HOME", "/explicit", "Path", "separate"), false);
        assertEquals("/explicit", environment.get("HOME"));
        assertEquals("/usr/bin", environment.get("PATH"));
        assertEquals("separate", environment.get("Path"));
        assertEquals("/run/agent", environment.get("SSH_AUTH_SOCK"));
    }

    @Test void onlyCommandRequestsInheritWithoutMutatingTheSavedPolicy() {
        Map<String, String> configured = Map.of("LANG", "C.UTF-8");
        Map<String, Object> policy = Map.of("environment", configured);
        MxcRuntime runtime = MxcRuntime.nativeHost(temporary.resolve("node"), temporary.resolve("sdk"), temporary.resolve("helpers"));
        MxcTransport transport = new MxcTransport(runtime, temporary.resolve("launcher"), policy);
        Map<String, Object> command = transport.buildRequest("execute", Map.of(), Duration.ofSeconds(10), 1024);
        @SuppressWarnings("unchecked") Map<String, String> environment = (Map<String, String>) command.get("environment");
        String key = runtime.windows() ? "USERPROFILE" : "HOME";
        assertNotNull(System.getenv(key));
        assertEquals(System.getenv(key), environment.get(key));
        assertEquals("C.UTF-8", environment.get("LANG"));
        assertTrue(environment.get("PATH").startsWith(temporary.toString()));
        assertSame(configured, policy.get("environment"));
        assertSame(configured, transport.buildRequest("read", Map.of(), Duration.ofSeconds(10), 1024).get("environment"));
        MxcTransport unconfigured = new MxcTransport(runtime, temporary.resolve("launcher"), Map.of());
        @SuppressWarnings("unchecked") Map<String, String> defaults = (Map<String, String>) unconfigured
                .buildRequest("execute", Map.of(), Duration.ofSeconds(10), 1024).get("environment");
        assertEquals(System.getenv(key), defaults.get(key));
    }
}
