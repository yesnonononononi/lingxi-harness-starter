package com.summit.sandbox.mxc;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class MxcPlatformProbeTest {
    @TempDir Path temporary;

    @Test void discoversHostCapabilitiesWithoutCreatingTheConfiguredStateDirectory() throws Exception {
        String node = System.getProperty("mxc.node");
        String sdk = System.getProperty("mxc.sdk");
        assumeTrue(node != null && sdk != null, "Configure host-native MXC resources");
        Path absent = temporary.resolve("must-not-be-created/helpers");
        MxcPlatformProbe.Result result = MxcPlatformProbe.check(MxcRuntime.nativeHost(Path.of(node), Path.of(sdk), absent));
        assertTrue(result.available(), result.detail());
        assertEquals(System.getProperty("os.name").toLowerCase().contains("windows") ? "BaseContainer" : "Bubblewrap", result.backend());
        assertFalse(Files.exists(absent.getParent()));
        try (Stream<Path> files = Files.list(temporary)) { assertEquals(0, files.count()); }
    }

    @Test void missingSdkFailsWithoutCreatingStateFiles() {
        String node = System.getProperty("mxc.node");
        assumeTrue(node != null, "Configure a host-native Node runtime");
        Path absent = temporary.resolve("state/helpers");
        assertThrows(IOException.class, () -> MxcPlatformProbe.check(MxcRuntime.nativeHost(Path.of(node), temporary.resolve("missing-sdk"), absent)));
        assertFalse(Files.exists(absent.getParent()));
    }
}
