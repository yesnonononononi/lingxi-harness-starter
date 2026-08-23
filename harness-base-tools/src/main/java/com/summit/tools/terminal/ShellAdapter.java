package com.summit.tools.terminal;

import com.summit.harnesscore.runtime.OsType;

import java.util.List;

public class ShellAdapter {
    public static List<String> shell(String command, OsType osType) {
        return switch (osType) {
            case LINUX -> List.of(
                    "/bin/sh",
                    "-c",
                    command
            );

            case MACOS -> List.of(
                    "/bin/bash",
                    "-c",
                    command
            );

            case WINDOWS,UNKNOWN -> List.of(
                    "powershell.exe",
                    "-NoProfile",
                    "-NonInteractive",
                    "-Command",
                    command
            );
        };
    }
}
