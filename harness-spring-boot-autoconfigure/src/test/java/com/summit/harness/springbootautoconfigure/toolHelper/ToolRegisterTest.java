package com.summit.harness.springbootautoconfigure.toolHelper;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.summit.core.anno.OtherProperty;
import com.summit.core.anno.Tool;
import com.summit.core.anno.ToolArgument;
import com.summit.core.anno.ToolProperty;
import com.summit.core.anno.type.ObjectValue;
import com.summit.core.anno.type.OptionalBoolean;
import com.summit.core.tool.ConcurrentPolicy;
import com.summit.core.tool.ToolDefinition;
import com.summit.core.tool.ToolExecuteResult;
import com.summit.core.tool.ToolExecution;
import com.summit.core.tool.ToolExecutor;
import com.summit.core.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ToolRegisterTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RecordingExecutor executor = new RecordingExecutor("injected dependency");

    @Test
    void registersAnnotatedBeanWithDefaultsAndTheManagedExecutor() throws Exception {
        try (AnnotationConfigApplicationContext context = context(DefaultDescriptor.class)) {
            ToolRegistry registry = register(context);
            ToolDefinition<?> definition = requireTool(registry, "default_tool");

            assertAll(
                    () -> assertEquals("default-id", definition.id()),
                    () -> assertEquals("Default description", definition.description()),
                    () -> assertEquals(5000, definition.maxOutput()),
                    () -> assertEquals(60_000L, definition.timeout()),
                    () -> assertEquals(ConcurrentPolicy.READ_ONLY, definition.concurrentPolicy()),
                    () -> assertTrue(definition.readOnly()),
                    () -> assertTrue(definition.allowConcurrent()),
                    () -> assertSame(executor, definition.executor()));
            assertEquals("injected dependency", definition.executor().execute(null).getToolOutput());
            assertEquals(objectMapper.readTree("""
                    {"type":"object","properties":{},"required":[]}
                    """), objectMapper.readTree(definition.parametersJsonSchema()));
        }
    }

    @Test
    void buildsRequiredOptionalAndTypedArgumentsWithoutLosingNames() throws Exception {
        try (AnnotationConfigApplicationContext context = context(SearchDescriptor.class)) {
            JsonNode schema = objectMapper.readTree(requireTool(register(context), "search_tool")
                    .parametersJsonSchema());

            assertEquals(objectMapper.readTree("""
                    {
                      "query":{"type":"string","description":"Find \\\"quoted\\\" text\\non disk"},
                      "limit":{"type":"integer","description":"Maximum results"},
                      "include_hidden":{"type":"boolean","description":""}
                    }
                    """), schema.get("properties"));
            assertEquals(objectMapper.readTree("[\"query\",\"include_hidden\"]"), schema.get("required"));
            assertEquals("object", schema.path("type").asText());
        }
    }

    @Test
    void carriesAdditionalSchemaPropertiesAndEscapesTheirValues() throws Exception {
        try (AnnotationConfigApplicationContext context = context(SearchDescriptor.class)) {
            JsonNode schema = objectMapper.readTree(requireTool(register(context), "search_tool")
                    .parametersJsonSchema());

            assertEquals("Search \"files\"\nC:\\workspace", schema.path("title").asText());
            assertEquals("https://example.test/tool.schema.json", schema.path("$schema").asText());
            assertTrue(schema.path("title").isTextual());
        }
    }

    @Test
    void appliesExplicitExecutionLimitsAndSerialPolicy() {
        try (AnnotationConfigApplicationContext context = context(SearchDescriptor.class)) {
            ToolDefinition<?> definition = requireTool(register(context), "search_tool");

            assertAll(
                    () -> assertEquals("search-id", definition.id()),
                    () -> assertEquals("Search files", definition.description()),
                    () -> assertEquals(1234, definition.maxOutput()),
                    () -> assertEquals(4321L, definition.timeout()),
                    () -> assertEquals(ConcurrentPolicy.SERIAL_MUTATION, definition.concurrentPolicy()),
                    () -> assertFalse(definition.allowConcurrent()),
                    () -> assertFalse(definition.readOnly()));
        }
    }

    @Test
    void registersTypedObjectSchemaThroughTheSameRegistryPath() throws Exception {
        try (AnnotationConfigApplicationContext context = context(TypedDescriptor.class)) {
            ToolDefinition<?> definition = requireTool(register(context), "typed_tool");
            assertSame(executor, definition.executor());
            assertEquals(objectMapper.readTree("{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}"),
                    objectMapper.readTree(definition.parametersJsonSchema()));
        }
    }

    @Test
    void rejectsInvalidTypedSchemaBeforeItEntersTheRegistry() {
        try (AnnotationConfigApplicationContext context = context(InvalidTypedDescriptor.class)) {
            ToolRegistry registry = new ToolRegistry(new ArrayList<>());
            RuntimeException error = assertThrows(RuntimeException.class, () -> run(context, registry));
            assertTrue(error.getMessage().contains("invalid-typed-id"));
            assertNull(registry.getTool("invalid_typed_tool"));
        }
    }

    @Test
    void registersMultipleAnnotatedBeansAlongsideAnExistingDefinition() {
        try (AnnotationConfigApplicationContext context = context(DefaultDescriptor.class, SearchDescriptor.class)) {
            ToolDefinition<ToolExecutor> existing = ToolDefinition.<ToolExecutor>builder()
                    .id("manual-id").name("manual_tool").executor(executor)
                    .maxOutput(20).timeout(1L).build();
            ToolRegistry registry = new ToolRegistry(new ArrayList<>(List.of(existing)));

            run(context, registry);

            assertSame(existing, registry.getTool("manual_tool"));
            assertNotNull(registry.getTool("default_tool"));
            assertNotNull(registry.getTool("search_tool"));
        }
    }

    @Test
    void ignoresUnannotatedBeansAndAnnotatedClassesOutsideTheContext() {
        try (AnnotationConfigApplicationContext context = context(UnannotatedDescriptor.class)) {
            ToolRegistry registry = register(context);

            assertNull(registry.getTool("default_tool"));
            assertNull(registry.getTool("search_tool"));
        }
    }

    @Test
    void skipsFactoryBeansWhoseProductTypeIsUnknown() {
        try (AnnotationConfigApplicationContext context = context(UnknownTypeFactory.class, DefaultDescriptor.class)) {
            assertNull(context.getType("unknownTypeFactory"));

            ToolRegistry registry = register(context);

            assertNotNull(registry.getTool("default_tool"));
            assertNull(registry.getTool("search_tool"));
        }
    }

    @Test
    void discoversAnnotationsOnCglibProxyTargets() {
        ProxyFactory factory = new ProxyFactory(new DefaultDescriptor());
        factory.setProxyTargetClass(true);
        Object proxy = factory.getProxy();
        assertTrue(AopUtils.isCglibProxy(proxy));
        try (AnnotationConfigApplicationContext context = context()) {
            context.registerBean("proxiedDescriptor", Object.class, () -> proxy);

            assertNotNull(register(context).getTool("default_tool"));
        }
    }

    @Test
    void discoversAnnotationsOnJdkProxyTargets() {
        ProxyFactory factory = new ProxyFactory(new DefaultDescriptor());
        factory.setInterfaces(Descriptor.class);
        Object proxy = factory.getProxy();
        assertTrue(AopUtils.isJdkDynamicProxy(proxy));
        try (AnnotationConfigApplicationContext context = context()) {
            context.registerBean("proxiedDescriptor", Object.class, () -> proxy);

            assertNotNull(register(context).getTool("default_tool"));
        }
    }

    @Test
    void missingExecutorFailsWithTheToolIdAndOriginalCause() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.register(DefaultDescriptor.class);
            context.refresh();
            ToolRegistry registry = new ToolRegistry(new ArrayList<>());

            RuntimeException error = assertThrows(RuntimeException.class, () -> run(context, registry));

            assertTrue(error.getMessage().contains("default-id"));
            assertInstanceOf(NoSuchBeanDefinitionException.class, error.getCause());
            assertNull(registry.getTool("default_tool"));
        }
    }

    @Test
    void duplicateAnnotationNamesFailWithoutReplacingTheFirstTool() {
        try (AnnotationConfigApplicationContext context = context(DefaultDescriptor.class, DuplicateDescriptor.class)) {
            ToolRegistry registry = new ToolRegistry(new ArrayList<>());

            RuntimeException error = assertThrows(RuntimeException.class, () -> run(context, registry));

            assertTrue(error.getMessage().contains("duplicate-id"));
            assertNotNull(error.getCause());
            assertTrue(error.getCause().getMessage().contains("default_tool"));
            assertEquals("default-id", requireTool(registry, "default_tool").id());
        }
    }

    private AnnotationConfigApplicationContext context(Class<?>... descriptors) {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.registerBean(RecordingExecutor.class, () -> executor);
        for (Class<?> descriptor : descriptors) {
            if (descriptor == UnknownTypeFactory.class) {
                context.registerBean("unknownTypeFactory", UnknownTypeFactory.class);
            } else {
                context.register(descriptor);
            }
        }
        context.refresh();
        return context;
    }

    private ToolRegistry register(AnnotationConfigApplicationContext context) {
        ToolRegistry registry = new ToolRegistry(new ArrayList<>());
        run(context, registry);
        return registry;
    }

    private void run(AnnotationConfigApplicationContext context, ToolRegistry registry) {
        new ToolRegister(registry, objectMapper, context).run(new DefaultApplicationArguments());
    }

    private ToolDefinition<?> requireTool(ToolRegistry registry, String name) {
        ToolDefinition<?> definition = registry.getTool(name);
        assertNotNull(definition, "Annotated tool was not registered: " + name);
        return definition;
    }

    public interface Descriptor {
        String label();
    }

    @Tool(id = "default-id", name = "default_tool", description = "Default description",
            executor = RecordingExecutor.class)
    public static class DefaultDescriptor implements Descriptor {
        @Override
        public String label() {
            return "default";
        }
    }

    @Tool(id = "search-id", name = "search_tool", description = "Search files",
            executor = RecordingExecutor.class, maxOutput = 1234, timeout = 4321L,
            concurrentPolicy = ConcurrentPolicy.SERIAL_MUTATION,
            props = {
                    @ToolProperty(key = "query", value = @ToolArgument(description = "Find \"quoted\" text\non disk")),
                    @ToolProperty(key = "limit", value = @ToolArgument(type = "integer",
                            description = "Maximum results", isRequired = false)),
                    @ToolProperty(key = "include_hidden", value = @ToolArgument(type = "boolean"))
            },
            others = {
                    @OtherProperty(key = "title", value = "Search \"files\"\nC:\\workspace"),
                    @OtherProperty(key = "$schema", value = "https://example.test/tool.schema.json")
            })
    static class SearchDescriptor {
    }

    @Tool(id = "duplicate-id", name = "default_tool", description = "Duplicate tool name",
            executor = RecordingExecutor.class)
    static class DuplicateDescriptor {
    }

    @Tool(id = "typed-id", name = "typed_tool", description = "Typed schema", executor = RecordingExecutor.class,
            parameters = @ObjectValue(additionalProperties = OptionalBoolean.FALSE))
    static class TypedDescriptor { }

    @Tool(id = "invalid-typed-id", name = "invalid_typed_tool", description = "Invalid typed schema", executor = RecordingExecutor.class,
            parameters = @ObjectValue(required = "missing"))
    static class InvalidTypedDescriptor { }

    static class UnannotatedDescriptor {
    }

    public static class UnknownTypeFactory implements FactoryBean<Object> {
        @Override
        public Object getObject() {
            return new Object();
        }

        @Override
        public Class<?> getObjectType() {
            return null;
        }
    }

    static class RecordingExecutor implements ToolExecutor {
        private final String dependency;

        RecordingExecutor(String dependency) {
            this.dependency = dependency;
        }

        @Override
        public ToolExecuteResult execute(ToolExecution execution) {
            return ToolExecuteResult.success(dependency);
        }
    }
}
