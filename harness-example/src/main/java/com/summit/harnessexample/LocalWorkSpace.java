package com.summit.harnessexample;

import com.summit.harnesscore.runtime.OsType;
import com.summit.harnesscore.runtime.RuntimeEnvironment;
import com.summit.harnesscore.runtime.ShellType;
import com.summit.harnesscore.runtime.Workspace;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@Component
public class LocalWorkSpace implements Workspace {
    private volatile boolean allowOutsideWorkspace = true;
    private volatile String workDir = null;

    @Override
    public String id() {
        return "local";
    }

    @Override
    public RuntimeEnvironment runtimeEnvironment() {
        return RuntimeEnvironment.builder()
                .osType(OsType.WINDOWS)
                .shellType(ShellType.PWSH)
                .charset(StandardCharsets.UTF_8)
                .envs(System.getenv())
                .build();
    }

    @Override
    public String workDir() {
        return workDir == null ? System.getProperty("user.dir") : workDir;
    }

    @Override
    public Path resolve(String path) {
        String workDir = this.workDir();
        Path wd = Paths.get(workDir).normalize();
        Path result = wd.resolve(path).normalize();
        if (!allowOutsideWorkspace) {
            if (!result.startsWith(wd)) {
                throw new IllegalArgumentException("File path is out of workspace");
            }
        }
        return result;
    }

    /**
     * Switches the workspace working directory.
     *
     * @param workDir the new absolute working directory
     * @throws IllegalArgumentException if the path is blank or does not point to an existing directory
     */
    public void updateWorkDir(String workDir) {
        if (workDir == null || workDir.isBlank()) {
            throw new IllegalArgumentException("workDir must not be blank");
        }
        Path dir = Paths.get(workDir).normalize();
        if (!Files.isDirectory(dir)) {
            throw new IllegalArgumentException("workDir does not exist or is not a directory: " + dir);
        }
        this.workDir = dir.toAbsolutePath().normalize().toString();
    }
}
