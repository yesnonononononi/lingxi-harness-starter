package com.summit.harness.springbootautoconfigure.config;

import com.summit.core.workspace.WorkspaceProvider;
import com.summit.core.workspace.WorkspaceResolver;
import com.summit.runtime.workspace.DefaultWorkspaceResolver;
import com.summit.runtime.workspace.LocalWorkspaceProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

import java.util.List;

/** Provider routing defaults without resource persistence or lifecycle scheduling. */
@AutoConfiguration
public class WorkspaceAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(name = "localWorkspaceProvider")
    public WorkspaceProvider localWorkspaceProvider() {
        return new LocalWorkspaceProvider();
    }

    @Bean
    @ConditionalOnMissingBean
    public WorkspaceResolver workspaceResolver(List<WorkspaceProvider> providers) {
        return new DefaultWorkspaceResolver(providers);
    }
}
