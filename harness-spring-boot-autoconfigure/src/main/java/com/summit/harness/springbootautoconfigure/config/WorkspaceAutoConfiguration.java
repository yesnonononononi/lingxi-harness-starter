package com.summit.harness.springbootautoconfigure.config;

import com.summit.core.workspace.WorkspaceManager;
import com.summit.core.workspace.WorkspaceProvider;
import com.summit.core.workspace.WorkspaceStore;
import com.summit.runtime.workspace.DefaultWorkspaceManager;
import com.summit.runtime.workspace.InMemoryWorkspaceStore;
import com.summit.runtime.workspace.LocalWorkspaceProvider;
import com.summit.runtime.workspace.WorkspaceDestroyReaper;
import com.summit.harness.springbootautoconfigure.properties.WorkspaceCleanupProperties;
import com.summit.harness.springbootautoconfigure.properties.WorkspaceRestoreProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

import java.util.List;

/** Provider-based workspace lifecycle defaults. */
@Slf4j
@AutoConfiguration
@EnableConfigurationProperties({WorkspaceCleanupProperties.class, WorkspaceRestoreProperties.class})
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

    /**
     * Counterpart of the destroy reaper: whatever survived the shutdown is adopted
     * at startup instead of waiting for a request to describe it again.
     */
    @Bean
    @ConditionalOnProperty(prefix = "lingxi.agent.sandbox.restore", name = "enabled", matchIfMissing = true)
    public ApplicationRunner managedWorkspaceRestorer(WorkspaceManager manager,
                                                      WorkspaceRestoreProperties properties) {
        return args -> restoreManagedRecords(manager, properties);
    }

    private static void restoreManagedRecords(WorkspaceManager manager, WorkspaceRestoreProperties properties) {
        try {
            int restored = manager.restoreManagedRecords();
            if (restored > 0) {
                log.info("adopted {} workspace resource(s) left running by an earlier run", restored);
            }
        } catch (RuntimeException failure) {
            if (properties.isFailFast()) {
                throw failure;
            }
            log.warn("adopting existing workspace resources failed; they will be provisioned on demand: {}",
                    failure.getMessage());
        }
    }
}
