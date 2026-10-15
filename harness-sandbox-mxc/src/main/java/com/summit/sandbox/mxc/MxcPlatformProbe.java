package com.summit.sandbox.mxc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.summit.core.runtime.ProcessRunner;
import com.summit.core.runtime.workspace.WorkspaceBridge;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Advisory host capability discovery, without helper extraction or a workspace/container launch. */
public final class MxcPlatformProbe {
    private MxcPlatformProbe() { }

    public record Result(boolean available, String backend, String detail) { }

    public static Result check(MxcRuntime runtime) throws IOException, InterruptedException {
        byte[] script;
        try (InputStream input = MxcPlatformProbe.class.getResourceAsStream("/mxc/platform-probe.mjs")) {
            if (input == null) throw new IOException("Missing MXC platform probe resource");
            script = input.readAllBytes();
        }
        List<String> command = new ArrayList<>();
        if (!runtime.windows()) command.addAll(List.of("/usr/bin/env", "-i", "PATH=/usr/bin:/bin"));
        command.addAll(List.of(runtime.nodeExecutable(), "--input-type=module", "-", runtime.sdkDirectory()));
        WorkspaceBridge.CommandResult output = ProcessRunner.run(command, null, StandardCharsets.UTF_8, script,
                runtime.startupTimeout().toMillis(), TimeUnit.MILLISECONDS, 16384);
        if (output.timedOut() || output.truncated() || output.exitCode() != 0) {
            throw new IOException("MXC platform probe failed: " + output.output());
        }
        JsonNode result = new ObjectMapper().readTree(output.output());
        if (result == null || !result.path("available").isBoolean() || !result.path("backend").isTextual()
                || !result.path("detail").isTextual()) throw new IOException("Malformed MXC platform probe response");
        return new Result(result.path("available").booleanValue(), result.path("backend").textValue(), result.path("detail").textValue());
    }
}
