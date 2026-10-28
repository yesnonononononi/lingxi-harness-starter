package com.summit.sandbox.mxc;

import com.summit.core.runtime.workspace.Workspace;
import com.summit.core.runtime.workspace.WorkspaceCommands;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Builds an independently staged source snapshot through the public Java provider. */
class MxcRealBuildTest {
    @Test void verifiesTheRealFrameworkSnapshotOffline() throws Exception {
        String build = System.getProperty("mxc.test.build.root");
        String dependencies = System.getProperty("mxc.test.build.dependencies");
        assumeTrue(build != null && dependencies != null, "Stage an isolated real-build snapshot explicitly");
        String node = System.getProperty("mxc.node");
        String sdk = System.getProperty("mxc.sdk");
        assumeTrue(!System.getProperty("os.name").toLowerCase().contains("windows"), "Linux real build suite");
        Path helperPath = Path.of(System.getProperty("mxc.test.root"), "helpers");
        MxcRuntime runtime = MxcRuntime.nativeHost(Path.of(node), Path.of(sdk), helperPath);
        Workspace workspace = new MxcWorkspaceProvider(runtime).resolve(new MxcWorkspaceSpec(build + "/harness", "build",
                List.of(dependencies + "/maven-repository", dependencies + "/tools"), List.of(build),
                Map.of("JAVA_HOME", "/usr/lib/jvm/java-21-openjdk-amd64", "PATH", "/usr/lib/jvm/java-21-openjdk-amd64/bin:/usr/bin:/bin",
                        "HOME", build + "/home", "TMPDIR", build + "/tmp", "MAVEN_SKIP_RC", "1", "MAVEN_OPTS", "-Xmx2048m")));
        String command = List.of("/bin/sh", dependencies + "/tools/maven/bin/mvn", "-o", "-B", "-ntp",
                        "-gs", build + "/empty-settings.xml", "-s", build + "/empty-settings.xml",
                        "-Dmaven.repo.local=" + dependencies + "/maven-repository", "-Duser.home=" + build + "/home",
                        "-Djava.io.tmpdir=" + build + "/tmp", "-Dgpg.skip=true", "-Dmaven.javadoc.skip=true", "clean", "verify")
                .stream().map(value -> "'" + value.replace("'", "'\\''") + "'").collect(java.util.stream.Collectors.joining(" "));
        WorkspaceCommands.CommandResult result = workspace.bridge().commands().execute(command, workspace.workDir(),
                StandardCharsets.UTF_8, 10, TimeUnit.MINUTES, 1024 * 1024);
        Files.writeString(runtime.hostPath(build + "/java-bridge-build.log"), result.output());
        assertFalse(result.timedOut(), result.output());
        assertFalse(result.truncated());
        assertEquals(0, result.exitCode(), result.output());
        assertTrue(result.output().contains("BUILD SUCCESS"));
    }
}
