package com.summit.harnessexample;

import com.summit.harnesscore.runtime.Workspace;
import com.summit.runtime.sandbox.DockerWorkspace;
import lombok.Data;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Switches the example agent between the local workspace and a Docker
 * sandbox via one property:
 *
 * <pre>
 * lingxi:
 *   agent:
 *     workspace: local        # or "docker"
 *     workspace-docker:
 *       container-id: my-agent-container
 *       workspace-root: /workspace
 * </pre>
 *
 * <p>When {@code docker} is selected, every tool call (read_file, edit_file,
 * terminal) executes inside the container through {@code docker exec}; the
 * host is never touched directly.</p>
 */
@Configuration
@ConditionalOnProperty(name = "lingxi.agent.workspace", havingValue = "docker")
public class SandboxWorkspaceConfig {

    @Bean
    @ConfigurationProperties(prefix = "lingxi.agent.workspace-docker")
    public DockerWorkspaceProperties dockerWorkspaceProperties() {
        return new DockerWorkspaceProperties();
    }

    @Bean
    public Workspace dockerWorkspace(DockerWorkspaceProperties properties) {
        return new DockerWorkspace("docker-" + properties.getContainerId(),
                properties.getContainerId(), properties.getWorkspaceRoot());
    }

    @Data
    public static class DockerWorkspaceProperties {
        /** Id (or name) of the running container the agent should work in. */
        private String containerId;
        /** Absolute in-container path the agent works from, e.g. /workspace. */
        private String workspaceRoot = "/workspace";
    }
}
