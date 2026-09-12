package com.summit.runtime.workspace;

import com.summit.core.runtime.Workspace;
import com.summit.core.workspace.ResourceOwnership;
import com.summit.core.workspace.WorkspaceProvider;
import com.summit.core.workspace.WorkspaceRecord;
import com.summit.core.workspace.WorkspaceRef;
import com.summit.core.workspace.WorkspaceSpec;
import com.summit.core.workspace.WorkspaceStatus;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** Local-machine workspace provider. */
public final class LocalWorkspaceProvider implements WorkspaceProvider {
    public static final String TYPE = "local";

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public WorkspaceRecord provision(WorkspaceRef ref, WorkspaceSpec spec) {
        requireSupported(spec);
        Path root = Path.of(spec.workDir()).toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            throw new IllegalArgumentException("local workspace directory does not exist: " + root);
        }
        return new WorkspaceRecord(ref, spec, Map.of(), ResourceOwnership.ATTACHED);
    }

    @Override
    public Workspace open(WorkspaceRecord record) {
        requireSupported(record.spec());
        WorkspaceStatus status = inspect(record);
        if (status.state() != WorkspaceStatus.State.READY) {
            throw new IllegalStateException(status.message());
        }
        return new LocalWorkspace(record.ref().id(), record.spec().workDir());
    }

    @Override
    public WorkspaceStatus inspect(WorkspaceRecord record) {
        try {
            Path root = Path.of(record.spec().workDir()).toAbsolutePath().normalize();
            return Files.isDirectory(root)
                    ? WorkspaceStatus.ready()
                    : new WorkspaceStatus(WorkspaceStatus.State.MISSING,
                    "local workspace directory does not exist: " + root);
        } catch (RuntimeException e) {
            return new WorkspaceStatus(WorkspaceStatus.State.ERROR, e.getMessage());
        }
    }

    private void requireSupported(WorkspaceSpec spec) {
        if (!TYPE.equalsIgnoreCase(spec.provider())) {
            throw new IllegalArgumentException("unsupported workspace provider: " + spec.provider());
        }
    }
}
