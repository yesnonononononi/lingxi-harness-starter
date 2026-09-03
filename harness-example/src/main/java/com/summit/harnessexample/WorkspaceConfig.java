package com.summit.harnessexample;

import com.summit.core.runtime.Workspace;
import com.summit.runtime.sandbox.DockerWorkspace;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class WorkspaceConfig {

    /**
     * Docker sandbox workspace. The container is created on demand when it does
     * not exist yet (an existing container with the same name is reused), and
     * the host project directory is bind-mounted into the container so agent
     * file edits are shared with the host in real time.
     */
    @Bean
    @ConditionalOnProperty(prefix = "lingxi.agent", name = "workspace", havingValue = "docker")
    public Workspace dockerWorkspace(
            @Value("${lingxi.agent.container-name:agent-sandbox}") String containerName,
            @Value("${lingxi.agent.container-image:alpine}") String image,
            @Value("${lingxi.agent.container-port:}") String port,
            @Value("${lingxi.agent.container-workdir:/workspace}") String workdir,
            @Value("${lingxi.agent.workspace-dir:}") String workspaceDir) {
        // Share the host project directory into the container; default to the
        // application's launch directory.
        String hostDir = workspaceDir.isBlank() ? System.getProperty("user.dir") : workspaceDir;
        return DockerWorkspace.newInstance(workdir, containerName, port.isBlank() ? null : port, hostDir, image);
    }
}
