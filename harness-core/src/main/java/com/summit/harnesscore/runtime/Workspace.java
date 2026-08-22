package com.summit.harnesscore.runtime;

import java.nio.file.Path;

public interface Workspace {
    String getWorkingDirectory();
    Workspace resolve(String path);
    Path normalize();
}
