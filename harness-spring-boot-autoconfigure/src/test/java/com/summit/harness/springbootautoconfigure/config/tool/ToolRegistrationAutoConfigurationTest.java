package com.summit.harness.springbootautoconfigure.config.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.summit.core.anno.Tool;
import com.summit.core.conversation.event.RuntimeEventPublisher;
import com.summit.core.interceptor.InterceptorProcessor;
import com.summit.core.tool.ToolExecuteResult;
import com.summit.core.tool.ToolExecution;
import com.summit.core.tool.ToolExecutor;
import com.summit.core.tool.ToolRegistry;
import com.summit.harness.springbootautoconfigure.toolHelper.ToolRegister;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ToolRegistrationAutoConfigurationTest {

    @Test
    void toolAutoConfigurationRegistersAnnotatedToolsWithoutApplicationPackageScanning() throws Exception {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(ObjectMapper.class, () -> new ObjectMapper());
            context.registerBean(RuntimeEventPublisher.class, () -> new RuntimeEventPublisher(List.of()));
            InterceptorProcessor<ToolExecution> interceptor = invocation -> null;
            context.registerBean("interceptorProcessor", InterceptorProcessor.class, () -> interceptor);
            context.register(CommonToolAutoConfiguration.class, AutoConfiguredExecutor.class);
            context.refresh();

            assertEquals(1, context.getBeansOfType(ToolRegister.class).size(),
                    "The starter must publish the annotation registrar without scanning framework packages");
            for (ApplicationRunner runner : context.getBeansOfType(ApplicationRunner.class).values()) {
                runner.run(new DefaultApplicationArguments());
            }

            ToolRegistry registry = context.getBean(ToolRegistry.class);
            assertNotNull(registry.getTool("auto_configured_tool"));
            assertSame(context.getBean(AutoConfiguredExecutor.class),
                    registry.getTool("auto_configured_tool").executor());
        }
    }

    @Tool(id = "auto-configured-id", name = "auto_configured_tool", description = "Auto-configured tool",
            executor = AutoConfiguredExecutor.class)
    static class AutoConfiguredExecutor implements ToolExecutor {
        @Override
        public ToolExecuteResult execute(ToolExecution execution) {
            return ToolExecuteResult.success("auto-configured");
        }
    }
}
