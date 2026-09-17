package com.summit.harness.springbootautoconfigure.conf;

import com.summit.core.workspace.WorkspaceManager;
import com.summit.core.workspace.WorkspaceProvider;
import com.summit.core.workspace.WorkspaceStore;
import com.summit.runtime.workspace.DefaultWorkspaceManager;
import com.summit.runtime.workspace.InMemoryWorkspaceStore;
import com.summit.runtime.workspace.LocalWorkspaceProvider;
import com.summit.runtime.workspace.WorkspaceDestroyReaper;
import com.summit.harness.springbootautoconfigure.properties.WorkspaceCleanupProperties;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

import java.util.List;

/** Provider-based workspace lifecycle defaults. */
@AutoConfiguration
@EnableConfigurationProperties(WorkspaceCleanupProperties.class)
public class WorkspaceAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(name = "localWorkspaceProvider")
    public WorkspaceProvider localWorkspaceProvider() {
        return new LocalWorkspaceProvider();
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

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    public WorkspaceDestroyReaper workspaceDestroyReaper(
            WorkspaceManager manager, WorkspaceCleanupProperties properties) {
        return new WorkspaceDestroyReaper(manager, properties.getInterval(),
                properties.getMaxBackoff(), properties.getMaxAttempts());
    }

}
