package com.summit.core.workspace;

import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

/** Stable, persistence-friendly reference to a workspace. */
public record WorkspaceRef(String id) implements Serializable {
    /** Number of digest bytes rendered into a derived id (two hex characters each). */
    private static final int DIGEST_BYTES = 8;

    public WorkspaceRef {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("workspace id must not be blank");
        }
    }

    public static WorkspaceRef of(String id) {
        return new WorkspaceRef(id);
    }

    /**
     * Derives the identity of the workspace that {@code naturalKey} describes.
     *
     * <p>The result is a pure function of its inputs, so a provider natural key
     * such as {@code "docker|/home/me/project"} always yields the same ref. That
     * is what lets a recreated process re-adopt the resource an earlier run
     * provisioned instead of creating a second one.</p>
     *
     * @param provider   provider kind, used as the id namespace
     * @param naturalKey provider-supplied natural key of the desired workspace
     */
    public static WorkspaceRef derived(String provider, String naturalKey) {
        String namespace = provider == null || provider.isBlank()
                ? "workspace"
                : provider.trim().toLowerCase(Locale.ROOT);
        byte[] digest = sha256(naturalKey == null ? "" : naturalKey);
        StringBuilder hex = new StringBuilder(DIGEST_BYTES * 2);
        for (int i = 0; i < DIGEST_BYTES; i++) {
            hex.append(String.format("%02x", digest[i]));
        }
        return new WorkspaceRef(namespace + "-" + hex);
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
