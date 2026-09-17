package com.summit.sandbox.docker;

import com.summit.core.workspace.WorkspaceRef;
import com.summit.core.workspace.WorkspaceSpec;
import com.summit.runtime.sandbox.DockerSandboxImage;
import lombok.Builder;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Desired configuration for a framework-managed Docker workspace. */
@Builder
public record DockerWorkspaceSpec(WorkspaceRef workspaceRef,String workDir, String containerName, String image,
                                  String hostDir, String port,
                                  boolean reuseByHostDirectory) implements WorkspaceSpec {
    public static final String PROVIDER = "docker";

    public  DockerWorkspaceSpec {
        if (!workDir.startsWith("/")) {
            throw new IllegalArgumentException("docker workDir must be an absolute container path");
        }
        // A toolchain-less base image is never a useful sandbox: fall back to the
        // framework image that already carries JDK, Maven, Git and Node.js.
        image = DockerSandboxImage.resolve(image);
        containerName = blankDefault(containerName,"lingXi-container"+ UUID.randomUUID());
    }

    @Override
    public WorkspaceRef workspaceRef() {
        return this.workspaceRef;
    }

    @Override
    public String provider() {
        return PROVIDER;
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

    private static String blankDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static void put(Map<String, String> values, String key, String value) {
        if (value != null && !value.isBlank()) {
            values.put(key, value.trim());
        }
    }
}
