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
    void publishesOneRegistrarWithoutApplicationPackageScanning() {
        try (AnnotationConfigApplicationContext context = context()) {
            context.refresh();

            assertEquals(1, context.getBeansOfType(ToolRegister.class).size());
            assertSame(context.getBean(ToolRegister.class), context.getBean(ApplicationRunner.class));
        }
    }

    @Test
    void applicationRegistrarReplacesTheDefaultByType() {
        try (AnnotationConfigApplicationContext context = context()) {
            context.registerBean("applicationToolRegister", ToolRegister.class, () -> new ToolRegister(
                    context.getBean(ToolRegistry.class), context.getBean(ObjectMapper.class), context));
            context.refresh();

            assertEquals(1, context.getBeansOfType(ToolRegister.class).size());
            assertSame(context.getBean("applicationToolRegister"), context.getBean(ToolRegister.class));
            assertFalse(context.containsBean("toolRegister"));
        }
    }

    @Test
    void existingComponentRegistrarDoesNotProduceASecondRunner() {
        try (AnnotationConfigApplicationContext context = context()) {
            context.register(ToolRegister.class);
            context.refresh();

            assertEquals(1, context.getBeansOfType(ToolRegister.class).size());
            assertEquals(1, context.getBeansOfType(ApplicationRunner.class).size());
        }
    }

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

    private AnnotationConfigApplicationContext context() {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.registerBean(ObjectMapper.class, () -> new ObjectMapper());
        context.registerBean(RuntimeEventPublisher.class, () -> new RuntimeEventPublisher(List.of()));
        InterceptorProcessor<ToolExecution> interceptor = invocation -> null;
        context.registerBean("interceptorProcessor", InterceptorProcessor.class, () -> interceptor);
        context.register(CommonToolAutoConfiguration.class);
        return context;
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
