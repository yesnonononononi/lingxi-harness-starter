package com.summit.sandbox.windows;

/**
 * Result of executing a command inside the Windows restricted-token sandbox.
 */
public record SandboxResult(int exitCode, String output, boolean timedOut) {
}
