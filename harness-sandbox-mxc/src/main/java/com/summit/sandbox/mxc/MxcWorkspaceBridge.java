package com.summit.sandbox.mxc;

import com.summit.core.runtime.workspace.*;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public final class MxcWorkspaceBridge implements WorkspaceBridge, WorkspaceStdioLauncher {
    private final MxcTransport transport;
    private final MxcWorkspaceFiles files;
    private final WorkspaceCommands commands;
    private Charset commandCharset = StandardCharsets.UTF_8;

    MxcWorkspaceBridge(MxcRuntime runtime, Path launcher, MxcWorkspaceSpec spec) {
        this.transport = new MxcTransport(runtime, launcher, buildPolicy(runtime, spec));
        this.files = new MxcWorkspaceFiles(runtime, transport);
        this.commands = new MxcCommandManager(runtime, transport);
    }

    @Override
    public WorkspaceFiles files() {
        return files;
    }

    @Override
    public WorkspaceCommands commands() {
        return commands;
    }

    @Override
    public WorkspaceStdioLaunch prepareStdio(List<String> command, Map<String, String> environment) throws IOException {
        return transport.prepareStdio(command, environment);
    }

    void verifyReady() throws IOException, InterruptedException {
        commandCharset = files.probe();
    }

    Charset commandCharset() {
        return commandCharset;
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
}
