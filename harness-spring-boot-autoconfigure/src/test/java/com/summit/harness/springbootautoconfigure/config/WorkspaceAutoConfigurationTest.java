package com.summit.harness.springbootautoconfigure.config;

import com.summit.core.runtime.workspace.Workspace;
import com.summit.core.workspace.BasicWorkspaceSpec;
import com.summit.core.workspace.WorkspaceProvider;
import com.summit.core.workspace.WorkspaceResolver;
import com.summit.core.workspace.WorkspaceSpec;
import com.summit.runtime.workspace.LocalWorkspace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class WorkspaceAutoConfigurationTest {
    @TempDir
    Path project;

    @Test
    void defaultRoutingIncludesExternalProvidersWithoutLifecycleBeans() {
        Workspace external = new LocalWorkspace("external-container", project.toString());
        WorkspaceProvider provider = new WorkspaceProvider() {
            public String type() { return "external"; }
            public Workspace resolve(WorkspaceSpec spec) { return external; }
        };
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean("externalProvider", WorkspaceProvider.class, () -> provider);
            context.register(WorkspaceAutoConfiguration.class);
            context.refresh();

            WorkspaceResolver resolver = context.getBean(WorkspaceResolver.class);
            assertSame(external, resolver.resolve(new BasicWorkspaceSpec("external", project.toString())));
            assertInstanceOf(LocalWorkspace.class,
                    resolver.resolve(new BasicWorkspaceSpec("local", project.toString())));
            assertEquals(2, context.getBeansOfType(WorkspaceProvider.class).size());
            assertFalse(context.containsBean("workspaceStore"));
            assertFalse(context.containsBean("workspaceDestroyReaper"));
            assertFalse(context.containsBean("managedWorkspaceRestorer"));
        }
    }

    @Test
    void applicationCanReplaceTheResolver() {
        Workspace external = new LocalWorkspace("custom", project.toString());
        WorkspaceResolver supplied = spec -> external;
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean("suppliedResolver", WorkspaceResolver.class, () -> supplied);
            context.register(WorkspaceAutoConfiguration.class);
            context.refresh();

            assertSame(supplied, context.getBean(WorkspaceResolver.class));
            assertEquals(1, context.getBeansOfType(WorkspaceResolver.class).size());
        }
    }
}
