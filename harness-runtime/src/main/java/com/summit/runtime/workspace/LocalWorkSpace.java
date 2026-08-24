package com.summit.runtime.workspace;



import com.summit.harnesscore.runtime.RuntimeEnvironment;
import com.summit.harnesscore.runtime.Workspace;
import lombok.Getter;

import java.net.URI;
import java.nio.charset.Charset;
import java.nio.file.Path;


@Getter
public class LocalWorkSpace implements Workspace {
    private final RuntimeEnvironment runTimeEnvironment;
    public LocalWorkSpace(RuntimeEnvironment runTimeEnvironment) {
        this.runTimeEnvironment =runTimeEnvironment;
    }

    @Override
    public RuntimeEnvironment runTimeEnvironment() {
        return this.runTimeEnvironment;
    }

    @Override
    public Path resolve(String path) {
        Path workDir = Path.of(runTimeEnvironment.workDir())
                .toAbsolutePath()
                .normalize();

        Path target = workDir
                .resolve(path)
                .normalize();

        if (!target.startsWith(workDir)) {
            throw new IllegalArgumentException(
                    "Path is outside workspace: " + path
            );
        }

        return target;
    }




}
