package com.summit.core.runtime.security;

import java.util.Objects;

/** Absolute path tree, or ** for all paths. No glob or environment expansion is implicit. */
public record FileSecurityRule(String path, SecurityAction read, SecurityAction write, SecurityAction delete) {
    public FileSecurityRule {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(read, "read");
        Objects.requireNonNull(write, "write");
        Objects.requireNonNull(delete, "delete");
        if (path.isBlank()) throw new IllegalArgumentException("Empty file rule path");
    }
    public SecurityAction action(FileOperation operation) {
        return switch (operation) { case READ -> read; case WRITE -> write; case DELETE -> delete; };
    }
}
