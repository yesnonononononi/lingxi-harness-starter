package com.summit.harnesscore.runtime;

import lombok.Getter;

import java.util.List;

@Getter
public enum ShellType {

    POWERSHELL("powershell.exe") {
        @Override
        public List<String> buildCommand(String command) {
            return List.of(
                    this.command,
                    "-NoProfile",
                    "-NonInteractive",
                    "-Command",
                    command
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
                    "-Command",
                    command
            );
        }
    },

    CMD("cmd.exe") {
        @Override
        public List<String> buildCommand(String command) {
            return List.of(
                    this.command,
                    "/c",
                    command
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
    };

    public final String command;

    ShellType(String command) {
        this.command = command;
    }

    public abstract List<String> buildCommand(String command);
}