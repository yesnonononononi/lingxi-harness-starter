package com.summit.core.runtime.security;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Tool admission only: this policy does not intercept a child process's system calls.
 * Denials dominate asks and allows, including across lexical paths and resolved aliases.
 */
public final class FileRulePolicy {
    private final List<FileSecurityRule> rules;
    private final SecurityAction fallback;

    public FileRulePolicy(List<FileSecurityRule> rules, SecurityAction fallback) {
        this.rules = List.copyOf(rules);
        this.fallback = Objects.requireNonNull(fallback, "fallback");
        for (FileSecurityRule rule : rules) {
            if (!rule.path().equals("**") && !Path.of(rule.path()).isAbsolute()) {
                throw new IllegalArgumentException("File rules require absolute paths: " + rule.path());
            }
        }
    }

    public SecurityDecision evaluate(Path target, FileOperation operation) throws IOException {
        Objects.requireNonNull(operation, "operation");
        Path lexical = target.toAbsolutePath().normalize();
        Path real = resolveExistingAncestor(lexical);
        SecurityDecision selected = null;
        for (FileSecurityRule rule : rules) {
            boolean matches = rule.path().equals("**");
            if (!matches) {
                Path prefix = Path.of(rule.path()).toAbsolutePath().normalize();
                Path resolved = resolveExistingAncestor(prefix);
                matches = lexical.startsWith(prefix) || real.startsWith(resolved)
                        || real.startsWith(prefix) || lexical.startsWith(resolved);
            }
            if (matches) {
                SecurityAction action = rule.action(operation);
                if (selected == null || weight(action) > weight(selected.action())) {
                    selected = new SecurityDecision(action, rule.path());
                }
            }
        }
        return selected == null ? new SecurityDecision(fallback, null) : selected;
    }

    private static int weight(SecurityAction action) {
        return switch (action) { case ALLOW -> 0; case ASK -> 1; case DENY -> 2; };
    }

    private static Path resolveExistingAncestor(Path target) throws IOException {
        List<Path> missing = new ArrayList<>();
        Path current = target;
        while (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
            Path name = current.getFileName();
            if (name == null) return current.toRealPath();
            missing.add(name);
            current = current.getParent();
        }
        Path result = current.toRealPath();
        for (int i = missing.size() - 1; i >= 0; i--) result = result.resolve(missing.get(i));
        return result;
    }
}
