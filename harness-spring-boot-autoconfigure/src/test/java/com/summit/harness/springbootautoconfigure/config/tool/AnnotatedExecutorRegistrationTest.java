package com.summit.harness.springbootautoconfigure.config.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.summit.core.anno.Tool;
import com.summit.core.conversation.event.RuntimeEventPublisher;
import com.summit.core.interceptor.InterceptorProcessor;
import com.summit.core.tool.ConcurrentPolicy;
import com.summit.core.tool.ToolDefinition;
import com.summit.core.tool.ToolExecuteResult;
import com.summit.core.tool.ToolExecution;
import com.summit.core.tool.ToolExecutor;
import com.summit.core.tool.ToolRegistry;
import com.summit.harness.springbootautoconfigure.toolHelper.ToolRegister;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ClassPathBeanDefinitionScanner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AnnotatedExecutorRegistrationTest {

    @Test
    void componentScanBindsTheAnnotatedExecutorWithItsInjectedDependency() {
        try (AnnotationConfigApplicationContext context = context(true)) {
            context.refresh();

            ToolRegistry registry = assertDoesNotThrow(() -> run(context));
            ToolDefinition<?> definition = registry.getTool("component_tool");

            assertNotNull(definition);
            assertSame(context.getBean("componentExecutor"), definition.executor());
            assertEquals("managed dependency", definition.executor().execute(null).getToolOutput());
            assertEquals("component-id", definition.id());
            assertEquals(List.of(definition), registry.getToolDefinitionList());
            assertEquals(Map.of("component_tool", definition), registry.getTools());
        }
    }

    @Test
    void multipleBeanDeclarationsAndRepeatedRunsRegisterTheExecutorOnce() {
        try (AnnotationConfigApplicationContext context = context(true)) {
            context.registerBean("executorAlias", AnnotatedExecutor.class,
                    () -> context.getBean("componentExecutor", AnnotatedExecutor.class));
            context.refresh();

            ToolRegistry registry = assertDoesNotThrow(() -> run(context));
            ToolDefinition<?> first = registry.getToolById("component-id");
            assertDoesNotThrow(() -> run(context));

            assertNotNull(first);
            assertSame(context.getBean("executorAlias"), first.executor());
            assertEquals(List.of(first), registry.getToolDefinitionList());
            assertEquals(1, registry.getTools().size());
        }
    }

    @Test
    void configuredDefinitionWinsOverAnnotationsOnTheExecutorAndConfigurationClass() {
        try (AnnotationConfigApplicationContext context = context(true)) {
            context.register(ExistingDefinitionConfiguration.class, DuplicateDescriptor.class);
            context.refresh();
            ToolDefinition<?> configured = context.getBean("configuredToolDefinition", ToolDefinition.class);

            ToolRegistry registry = assertDoesNotThrow(() -> run(context));
            assertDoesNotThrow(() -> run(context));

            assertSame(configured, registry.getTool("component_tool"));
            assertEquals(321, configured.maxOutput());
            assertEquals(9L, configured.timeout());
            assertEquals("Configured definition", configured.description());
            assertSame(context.getBean("componentExecutor"), configured.executor());
            assertEquals(List.of(configured), registry.getToolDefinitionList());
            assertEquals(1, registry.getTools().size());
        }
    }

    @Test
    void configuredIdAlsoSuppressesAnnotationsThatDeclareADifferentName() {
        try (AnnotationConfigApplicationContext context = context(true)) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test",
                    Map.of("test.tool.name", "configured_name")));
            context.register(ExistingDefinitionConfiguration.class);
            context.refresh();
            ToolDefinition<?> configured = context.getBean("configuredToolDefinition", ToolDefinition.class);

            ToolRegistry registry = assertDoesNotThrow(() -> run(context));

            assertSame(configured, registry.getToolById("component-id"));
            assertSame(configured, registry.getTool("configured_name"));
            assertNull(registry.getTool("component_tool"));
            assertEquals(List.of(configured), registry.getToolDefinitionList());
        }
    }

    @Test
    void differentIdsCannotSilentlyOverwriteTheSameConfiguredName() {
        try (AnnotationConfigApplicationContext context = context(true)) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test",
                    Map.of("test.tool.id", "configured-id")));
            context.register(ExistingDefinitionConfiguration.class);
            context.refresh();
            ToolDefinition<?> configured = context.getBean("configuredToolDefinition", ToolDefinition.class);

            RuntimeException error = assertThrows(RuntimeException.class, () -> run(context));

            assertTrue(error.getMessage().contains("component-id"));
            assertInstanceOf(IllegalArgumentException.class, error.getCause());
            assertTrue(error.getCause().getMessage().contains("configured-id"));
            ToolRegistry registry = context.getBean(ToolRegistry.class);
            assertSame(configured, registry.getTool("component_tool"));
            assertEquals(List.of(configured), registry.getToolDefinitionList());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void bindsTheManagedExecutorForBothJdkAndCglibProxies(boolean classProxy) {
        ProxyFactory factory = new ProxyFactory(new AnnotatedExecutor(new ExecutorDependency("proxied dependency")));
        factory.setInterfaces(ToolExecutor.class);
        factory.setProxyTargetClass(classProxy);
        ToolExecutor proxy = (ToolExecutor) factory.getProxy();
        assertEquals(classProxy, AopUtils.isCglibProxy(proxy));
        assertEquals(!classProxy, AopUtils.isJdkDynamicProxy(proxy));
        try (AnnotationConfigApplicationContext context = context(false)) {
            context.registerBean("componentExecutor", ToolExecutor.class, () -> proxy);
            context.refresh();

            ToolRegistry registry = assertDoesNotThrow(() -> run(context));
            ToolDefinition<?> definition = registry.getToolById("component-id");

            assertNotNull(definition);
            assertSame(proxy, definition.executor());
            assertEquals("proxied dependency", definition.executor().execute(null).getToolOutput());
            assertEquals(List.of(definition), registry.getToolDefinitionList());
        }
    }

    private AnnotationConfigApplicationContext context(boolean scanComponent) {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.registerBean(ObjectMapper.class, () -> new ObjectMapper());
        context.registerBean(RuntimeEventPublisher.class, () -> new RuntimeEventPublisher(List.of()));
        InterceptorProcessor<ToolExecution> interceptor = invocation -> null;
        context.registerBean("interceptorProcessor", InterceptorProcessor.class, () -> interceptor);
        context.registerBean(ExecutorDependency.class, () -> new ExecutorDependency("managed dependency"));
        context.register(CommonToolAutoConfiguration.class);
        if (scanComponent) {
            ClassPathBeanDefinitionScanner scanner = new ClassPathBeanDefinitionScanner(context, false);
            scanner.addIncludeFilter(new AssignableTypeFilter(AnnotatedExecutor.class));
            scanner.scan(AnnotatedExecutor.class.getPackageName());
        }
        return context;
    }

    private ToolRegistry run(AnnotationConfigApplicationContext context) {
        context.getBean(ToolRegister.class).run(new DefaultApplicationArguments());
        return context.getBean(ToolRegistry.class);
    }

    public record ExecutorDependency(String value) {
    }

    @Component("componentExecutor")
    @Tool(id = "component-id", name = "component_tool", description = "Annotated component",
            executor = AnnotatedExecutor.class)
    public static class AnnotatedExecutor implements ToolExecutor {
        private final ExecutorDependency dependency;

        public AnnotatedExecutor(ExecutorDependency dependency) {
            this.dependency = dependency;
        }

        @Override
        public ToolExecuteResult execute(ToolExecution execution) {
            return ToolExecuteResult.success(dependency.value());
        }
    }

    @Configuration(proxyBeanMethods = false)
    @Tool(id = "component-id", name = "component_tool", description = "Configuration annotation",
            executor = AnnotatedExecutor.class)
    static class ExistingDefinitionConfiguration {
        @Bean
        ToolDefinition<AnnotatedExecutor> configuredToolDefinition(AnnotatedExecutor executor, Environment environment) {
            return ToolDefinition.<AnnotatedExecutor>builder()
                    .id(environment.getProperty("test.tool.id", "component-id"))
                    .name(environment.getProperty("test.tool.name", "component_tool"))
                    .description("Configured definition").executor(executor)
                    .parametersJsonSchema("{\"type\":\"object\",\"properties\":{}}")
                    .maxOutput(321).timeout(9L).concurrentPolicy(ConcurrentPolicy.READ_ONLY).build();
        }
    }

    @Tool(id = "component-id", name = "ignored_annotation", description = "Already configured",
            executor = MissingExecutor.class)
    static class DuplicateDescriptor {
    }

    public static class MissingExecutor implements ToolExecutor {
        @Override
        public ToolExecuteResult execute(ToolExecution execution) {
            return ToolExecuteResult.success("unused");
        }
    }
}
