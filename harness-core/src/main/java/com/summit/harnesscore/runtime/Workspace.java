package com.summit.harnesscore.runtime;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.Locale;

@Data
public abstract class Workspace {
    public final String workDir;
    public final Charset charset;
    public final OsType osType;

    public Workspace(String workDir, Charset charset) {
        this.workDir = workDir;
        this.charset = charset;
        this.osType = getOsType();
    }

    public Workspace(Charset charset, String workDir, OsType osType) {
        this.charset = charset;
        this.workDir = workDir;
        this.osType = osType;
    }

    public abstract Workspace resolve(String path);


    public Path normalize() {
        return Path.of(this.workDir).normalize();
    }
    public OsType getOsType() {
        String osName = System.getProperty("os.name");

        if (osName == null) {
            return OsType.UNKNOWN;
        }

        String normalized = osName.toLowerCase(Locale.ROOT);

        if (normalized.contains("win")) {
            return OsType.WINDOWS;
        }

        if (normalized.contains("mac") ||
                normalized.contains("darwin")) {
            return OsType.MACOS;
        }

        if (normalized.contains("nux") ||
                normalized.contains("nix")) {
            return OsType.LINUX;
        }

        return OsType.UNKNOWN;

    }

}
