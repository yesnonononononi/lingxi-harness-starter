package com.summit.core.runtime.workspace;

import lombok.Getter;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
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

    PWSH("pwsh.exe") {
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
            return List.of(
                    this.command,
                    "/d",
                    "/s",
                    "/c",
                    "\"" + command + "\""
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



    public final String command;

    ShellType(String command) {
        this.command = command;
    }

    public abstract List<String> buildCommand(String command);

    private static String encodePowerShell(String command) {
        // Native Windows argument parsing must not consume script quotes before PowerShell sees them.
        return Base64.getEncoder().encodeToString(command.getBytes(StandardCharsets.UTF_16LE));
    }
}
