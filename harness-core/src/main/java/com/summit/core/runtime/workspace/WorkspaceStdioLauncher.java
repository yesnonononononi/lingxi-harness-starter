package com.summit.core.runtime.workspace;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/** Optional bridge capability for a persistent, isolated, bidirectional stdio process. */
public interface WorkspaceStdioLauncher {
    WorkspaceStdioLaunch prepareStdio(List<String> command, Map<String, String> environment) throws IOException;
}
