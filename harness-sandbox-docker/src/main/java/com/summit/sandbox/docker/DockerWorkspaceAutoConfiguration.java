package com.summit.sandbox.docker;

import com.summit.core.workspace.WorkspaceProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/** Registers Docker workspace support when this optional module is installed. */
@AutoConfiguration
@ConditionalOnClass(DockerWorkspaceProvider.class)
public class DockerWorkspaceAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(name = "dockerWorkspaceProvider")
    public WorkspaceProvider dockerWorkspaceProvider() {
        return new DockerWorkspaceProvider();
    }
}
