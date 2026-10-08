package com.summit.sandbox.docker;

/**
 * The container image a Docker sandbox is built from.
 *
 * <p>The framework ships a general-purpose development image instead of a bare
 * {@code alpine}: a sandbox is only useful to an agent when the runtimes a
 * project needs are already inside it, and installing them at task time inside
 * the container is exactly what the sandbox is meant to prevent. The image
 * definition and its build script live in
 * {@code harness-sandbox-docker/src/main/docker}.</p>
 *
 * <p>Content of {@link #DEFAULT}: JDK 21 (Temurin), Maven, Git, Node.js, npm
 * and bash.</p>
 */
public final class DockerSandboxImage {

    /** Image built by the framework's own Dockerfile; used when no image is configured. */
    public static final String DEFAULT = "lingxi-agent-dev:latest";

    private DockerSandboxImage() {
    }

    /**
     * Returns the image to run, falling back to {@link #DEFAULT} for a blank
     * value so a sandbox is never silently created from a toolchain-less base
     * image.
     */
    public static String resolve(String configured) {
        return configured == null || configured.isBlank() ? DEFAULT : configured.trim();
    }

    /**
     * Compares two image references tolerantly: {@code repo} and
     * {@code repo:latest} denote the same image, while different tags or
     * registries do not.
     */
    public static boolean same(String left, String right) {
        if (left == null || right == null) {
            return false;
        }
        return normalize(left).equals(normalize(right));
    }

    /** Strips an implicit {@code :latest} tag so equal references compare equal. */
    private static String normalize(String image) {
        String value = image.trim();
        int slash = value.lastIndexOf('/');
        if (value.lastIndexOf(':') <= slash) {
            return value + ":latest";
        }
        return value;
    }
}
