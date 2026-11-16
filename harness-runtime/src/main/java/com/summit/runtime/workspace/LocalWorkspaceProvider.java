package com.summit.runtime.workspace;

import com.summit.core.runtime.workspace.Workspace;
import com.summit.core.workspace.WorkspaceOptions;
import com.summit.core.workspace.WorkspaceProvider;
import com.summit.core.workspace.WorkspaceRef;
import com.summit.core.workspace.WorkspaceSpec;

import java.nio.file.Files;
import java.nio.file.Path;

/** Resolves existing host directories without registering or owning them. */
public final class LocalWorkspaceProvider implements WorkspaceProvider {
    public static final String TYPE = "local";

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public Workspace resolve(WorkspaceSpec spec) {
        return resolve(spec, null);
    }

    @Override
    public Workspace resolve(WorkspaceSpec spec, WorkspaceOptions options) {
        if (!TYPE.equalsIgnoreCase(spec.provider().trim())) {
            throw new IllegalArgumentException("unsupported workspace provider: " + spec.provider());
        }
        String effectiveWorkDir = options != null && options.getWorkDir() != null && !options.getWorkDir().isBlank()
                ? options.getWorkDir() : spec.workDir();
        Path root = Path.of(effectiveWorkDir).toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            throw new IllegalArgumentException("local workspace directory does not exist: " + root);
        }
        String scope = spec.scope() == null ? "" : spec.scope().trim();
        String identity = TYPE + "|" + scope + "|" + root;
        return new LocalWorkspace(WorkspaceRef.derived(TYPE, identity).id(), root.toString());
    }
}
