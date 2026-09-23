package com.summit.sandbox.docker;

import com.summit.core.workspace.WorkspaceSpec;
import com.summit.runtime.sandbox.DockerSandboxImage;
import lombok.Builder;

import java.util.LinkedHashMap;
import java.util.Map;

/** Desired configuration for a framework-managed Docker workspace. */
@Builder
public record DockerWorkspaceSpec(String workDir, String containerName, String image,
                                  String hostDir, String port, String principal,
                                  boolean reuseByHostDirectory) implements WorkspaceSpec {
    public static final String PROVIDER = "docker";

    public  DockerWorkspaceSpec {
        if (!workDir.startsWith("/")) {
            throw new IllegalArgumentException("docker workDir must be an absolute container path");
        }
        // A toolchain-less base image is never a useful sandbox: fall back to the
        // framework image that already carries JDK, Maven, Git and Node.js.
        image = DockerSandboxImage.resolve(image);
        // Left blank so the provider can derive a stable name from the workspace
        // identity; a random name would make every provision create a container.
        containerName = containerName == null || containerName.isBlank() ? null : containerName.trim();
    }

    @Override
    public String provider() {
        return PROVIDER;
    }

    /**
     * The principal this sandbox belongs to. It takes part in the derived
     * identity, so two principals mounting the same host directory get two
     * containers instead of sharing one.
     */
    @Override
    public String scope() {
        return principal == null || principal.isBlank() ? null : principal.trim();
    }

    @Override
    public Map<String, String> configuration() {
        Map<String, String> values = new LinkedHashMap<>();
        put(values, DockerWorkspaceProvider.CONTAINER_NAME, containerName);
        put(values, DockerWorkspaceProvider.IMAGE, image);
        put(values, DockerWorkspaceProvider.HOST_DIR, hostDir);
        put(values, DockerWorkspaceProvider.PORT, port);
        values.put(DockerWorkspaceProvider.REUSE_BY_HOST_DIRECTORY,
                Boolean.toString(reuseByHostDirectory));
        return Map.copyOf(values);
    }

    private static void put(Map<String, String> values, String key, String value) {
        if (value != null && !value.isBlank()) {
            values.put(key, value.trim());
        }
    }
}
