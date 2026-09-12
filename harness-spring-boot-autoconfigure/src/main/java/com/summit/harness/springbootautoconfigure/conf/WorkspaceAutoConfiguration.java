package com.summit.harness.springbootautoconfigure.conf;

import com.summit.core.workspace.WorkspaceManager;
import com.summit.core.workspace.WorkspaceProvider;
import com.summit.core.workspace.WorkspaceStore;
import com.summit.runtime.workspace.DefaultWorkspaceManager;
import com.summit.runtime.workspace.InMemoryWorkspaceStore;
import com.summit.runtime.workspace.LocalWorkspaceProvider;
import com.summit.sandbox.docker.DockerWorkspaceProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

import java.util.List;

/** Provider-based workspace lifecycle defaults. */
@AutoConfiguration
public class WorkspaceAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(name = "localWorkspaceProvider")
    public WorkspaceProvider localWorkspaceProvider() {
        return new LocalWorkspaceProvider();
    }

    @Bean
    @ConditionalOnMissingBean(name = "dockerWorkspaceProvider")
    public WorkspaceProvider dockerWorkspaceProvider() {
        return new DockerWorkspaceProvider();
    }

    @Bean
    @ConditionalOnMissingBean
    public WorkspaceStore workspaceStore() {
        return new InMemoryWorkspaceStore();
    }

    @Bean
    @ConditionalOnMissingBean
    public WorkspaceManager workspaceManager(List<WorkspaceProvider> providers, WorkspaceStore store) {
        return new DefaultWorkspaceManager(providers, store);
    }
}
