package com.summit.sandbox.mxc;


import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Every file and shell operation runs under the same host-native isolation policy.
 */
public final class MxcWorkspaceBridge extends AbstractFileWorkspaceBridge {
    MxcWorkspaceBridge(MxcRuntime runtime, Path launcher, MxcWorkspaceSpec spec) {
        super(runtime, launcher, buildPolicy(runtime, spec));
    }

    private static Map<String, Object> buildPolicy(MxcRuntime runtime, MxcWorkspaceSpec spec) {
        return Map.of("root", runtime.nativePath(spec.workDir()), "sdkDirectory", runtime.sdkDirectory(),
                "readonlyPaths", spec.readonlyPaths().stream().map(runtime::nativePath).toList(),
                "readwritePaths", spec.readwritePaths().stream().map(runtime::nativePath).toList(),
                "environment", spec.environment(), "writable", spec.writable(),
                "deniedPaths", spec.securityPolicy().deniedPaths().stream().map(runtime::nativePath).toList(),
                "ambientRoots", spec.securityPolicy().ambientRoots().stream().map(runtime::nativePath).toList(),
                "allowOutboundNetwork", spec.securityPolicy().allowOutboundNetwork());
    }


    @Override
    public CommandResult execute(String command, String workDir, Charset charset, long timeout,
                                 TimeUnit unit, long maxOutputChars) throws IOException, InterruptedException {
        return commandManager.execute(command, workDir, charset, timeout, unit, maxOutputChars);
    }

}
