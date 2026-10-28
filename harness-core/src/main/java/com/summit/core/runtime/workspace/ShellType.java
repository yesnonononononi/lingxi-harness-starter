package com.summit.core.runtime.workspace;

import lombok.Getter;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.Locale;
import java.util.List;

@Getter
public enum ShellType {

    POWERSHELL("powershell.exe") {
        @Override
        public List<String> buildCommand(String command) {
            return List.of(
                    this.command,
                    "-NoProfile",
                    "-NonInteractive" ,
                    "-EncodedCommand",
                    encodePowerShell(command)
            );
        }
    },

    /** Bare name: Windows resolves pwsh to pwsh.exe, Linux/macOS resolve the same name on PATH. */
    PWSH("pwsh") {
        @Override
        public List<String> buildCommand(String command) {
            return List.of(
                    this.command,
                    "-NoProfile",
                    "-NonInteractive",
                    "-EncodedCommand",
                    encodePowerShell(command)
            );
        }
    },

    CMD("cmd.exe") {
        @Override
        public List<String> buildCommand(String command) {
            // cmd 在进程启动时用控制台输入代码页把命令行转成字节，之后程序按该代码页输出；引导切到 65001，
            // 让按控制台代码页输出字节的程序（git、node 等）与 UTF-8 解码一致。注意 cmd 内置命令（echo 等）
            // 用的是启动时解析出的 ANSI 字节，chcp 对它们无效；脚本的引号/管道/重定向语义不受影响。
            return List.of(
                    this.command,
                    "/d",
                    "/s",
                    "/c",
                    "\"" + UTF8_CODE_PAGE_BOOTSTRAP + command + "\""
            );
        }
    },

    BASH("/bin/bash") {
        @Override
        public List<String> buildCommand(String command) {
            return List.of(
                    this.command,
                    "-c",
                    command
            );
        }
    },

    ZSH("/bin/zsh") {
        @Override
        public List<String> buildCommand(String command) {
            return List.of(
                    this.command,
                    "-c",
                    command
            );
        }
    },

    /** POSIX sh — present in minimal images such as alpine. */
    SH("/bin/sh") {
        @Override
        public List<String> buildCommand(String command) {
            return List.of(
                    this.command,
                    "-c",
                    command
            );
        }
    };



    /** CMD 的输出代码页引导；拼在脚本前面，与脚本用 & 顺序连接。 */
    static final String UTF8_CODE_PAGE_BOOTSTRAP = "chcp 65001>nul & ";

    public final String command;

    ShellType(String command) {
        this.command = command;
    }

    public abstract List<String> buildCommand(String command);
    /** Stable lowercase id used by tool arguments and approval snapshots. */
    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Whether a caller may explicitly request this shell on the given OS. */
    public boolean isSelectableOn(OsType os) {
        if (os == null) {
            return false;
        }
        return switch (os) {
            case WINDOWS -> this == CMD || this == PWSH || this == POWERSHELL;
            case LINUX -> this == BASH || this == SH || this == PWSH;
            case MACOS -> this == ZSH || this == BASH || this == SH || this == PWSH;
            case UNKNOWN -> false;
        };
    }

    /** Requestable ids for an OS, in declaration order, for schemas and error messages. */
    public static List<String> selectableIds(OsType os) {
        return Arrays.stream(values()).filter(shell -> shell.isSelectableOn(os)).map(ShellType::id).toList();
    }

    /**
     * Resolves a requested id for an OS. Blank means "no request" and yields {@code null} so the
     * workspace default applies; an unknown or platform-foreign id fails instead of silently
     * falling back to another interpreter.
     */
    public static ShellType select(String requested, OsType os) {
        if (requested == null || requested.isBlank()) {
            return null;
        }
        String id = requested.strip().toLowerCase(Locale.ROOT);
        for (ShellType shell : values()) {
            if (shell.id().equals(id) && shell.isSelectableOn(os)) {
                return shell;
            }
        }
        throw new IllegalArgumentException(
                "shell '" + requested + "' is not supported on " + os + "; supported: " + selectableIds(os));
    }


    private static String encodePowerShell(String command) {
        // Native Windows argument parsing must not consume script quotes before PowerShell sees them.
        // Redirected Windows PowerShell falls back to the OEM code page; the host always decodes UTF-8.
        String script = "[Console]::OutputEncoding=[Text.UTF8Encoding]::new($false);"
                + "$OutputEncoding=[Console]::OutputEncoding;" + command;
        return Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE));
    }
}
