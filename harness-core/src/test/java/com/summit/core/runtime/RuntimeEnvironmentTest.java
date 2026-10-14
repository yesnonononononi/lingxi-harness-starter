package com.summit.core.runtime;

import com.summit.core.runtime.workspace.ShellType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RuntimeEnvironmentTest {
    @TempDir Path temporary;

    @Test
    void deploymentExecutableReplacesOnlyArgvZeroAndDoesNotAlterRawScripts() {
        String script = "value='A B'; printf '%s' \"$value\"";
        String executable = temporary.resolve("runtime space & $/bin/bash").toString();
        RuntimeEnvironment environment = RuntimeEnvironment.builder().shellType(ShellType.BASH)
                .shellExecutable(executable).build();
        assertEquals(List.of(executable, "-c", script), environment.buildCommand(script));
        assertEquals(ShellType.BASH.buildCommand(script), RuntimeEnvironment.builder().shellType(ShellType.BASH).build().buildCommand(script));
        assertEquals(Map.of(), environment.envs());
        assertThrows(IllegalArgumentException.class, () -> RuntimeEnvironment.builder().shellExecutable("relative/bash").build());
    }
}
