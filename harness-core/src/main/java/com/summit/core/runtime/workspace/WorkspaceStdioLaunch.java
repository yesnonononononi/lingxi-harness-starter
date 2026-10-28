package com.summit.core.runtime.workspace;

import java.io.Closeable;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Immutable launch recipe. Closing it must cancel the entire session and release control files. */
public record WorkspaceStdioLaunch(List<String> command, Map<String, String> environment,
                                   Closeable cleanup) implements Closeable {
    public WorkspaceStdioLaunch {
        command = List.copyOf(command);
        environment = Map.copyOf(environment);
        Objects.requireNonNull(cleanup, "cleanup");
        if (command.isEmpty()) throw new IllegalArgumentException("Empty stdio command");
    }
    @Override public void close() throws IOException { cleanup.close(); }
}
