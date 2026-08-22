package com.summit.runtime.workspace;

import com.summit.harnesscore.runtime.Workspace;
import lombok.Getter;

import java.nio.file.Path;

@Getter
public class LocalWorkSpace implements Workspace {
    private final String workDir;

    public LocalWorkSpace(String workDir) {
        this.workDir = workDir;
        System.out.println("LocalWorkSpace: " + workDir);
    }

    @Override
    public String getWorkingDirectory() {
        return this.workDir;
    }

    @Override
    public Workspace resolve(String path) {
        return new LocalWorkSpace(this.workDir + "/" + path);
    }

    @Override
    public Path normalize() {
        return Path.of(this.workDir).normalize();
    }

}
