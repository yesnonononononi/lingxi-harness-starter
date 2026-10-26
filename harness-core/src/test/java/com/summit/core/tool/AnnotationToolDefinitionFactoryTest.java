package com.summit.core.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.summit.core.anno.OtherProperty;
import com.summit.core.anno.Tool;
import com.summit.core.anno.ToolArgument;
import com.summit.core.anno.ToolProperty;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AnnotationToolDefinitionFactoryTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final AnnotationToolDefinitionFactory factory = new AnnotationToolDefinitionFactory(mapper);

    @Test
    void preservesNestedSchemaConstraintsAndTheSuppliedExecutor() throws Exception {
        TestExecutor executor = new TestExecutor();
        ToolDefinition<TestExecutor> definition = factory.create(RawSchema.class.getAnnotation(Tool.class), executor);
        JsonNode schema = mapper.readTree(definition.parametersJsonSchema());

        assertSame(executor, definition.executor());
        assertEquals("schema-id", definition.id());
        assertEquals(17L, definition.timeout());
        assertEquals(123, definition.maxOutput());
        assertEquals(ConcurrentPolicy.SERIAL_MUTATION, definition.concurrentPolicy());
        assertEquals(mapper.readTree("[\"read\",\"write\"]"), schema.at("/properties/action/enum"));
        assertEquals("string", schema.at("/properties/paths/items/type").asText());
        assertEquals(1, schema.at("/properties/paths/minItems").asInt());
        assertFalse(schema.at("/properties/background/default").asBoolean(true));
        assertFalse(schema.path("additionalProperties").asBoolean(true));
        assertEquals(mapper.readTree("[\"action\",\"paths\"]"), schema.path("required"));
    }

    @Test
    void malformedSchemaReportsTheToolIdAndRetainsTheParserFailure() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> factory.create(MalformedSchema.class.getAnnotation(Tool.class), new TestExecutor()));

        assertTrue(error.getMessage().contains("malformed-id"));
        assertNotNull(error.getCause());
    }

    @Test
    void schemaMustBeAnObject() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> factory.create(ArraySchema.class.getAnnotation(Tool.class), new TestExecutor()));

        assertTrue(error.getMessage().contains("array-id"));
    }

    @Test
    void trailingContentCannotBeSilentlyDiscarded() {
        assertThrows(IllegalArgumentException.class,
                () -> factory.create(TrailingSchema.class.getAnnotation(Tool.class), new TestExecutor()));
    }

    @Test
    void rawSchemaCannotSilentlyIgnoreStructuredProperties() {
        assertThrows(IllegalArgumentException.class,
                () -> factory.create(MixedProperties.class.getAnnotation(Tool.class), new TestExecutor()));
    }

    @Test
    void rawSchemaCannotSilentlyIgnoreAdditionalProperties() {
        assertThrows(IllegalArgumentException.class,
                () -> factory.create(MixedExtras.class.getAnnotation(Tool.class), new TestExecutor()));
    }

    @Tool(id = "schema-id", name = "schema_tool", description = "Complete schema",
            executor = TestExecutor.class, timeout = 17L, maxOutput = 123,
            concurrentPolicy = ConcurrentPolicy.SERIAL_MUTATION,
            parametersJsonSchema = """
                    {"type":"object","properties":{
                      "action":{"type":"string","enum":["read","write"]},
                      "paths":{"type":"array","items":{"type":"string"},"minItems":1},
                      "background":{"type":"boolean","default":false}
                    },"required":["action","paths"],"additionalProperties":false}
                    """)
    static class RawSchema {
    }

    @Tool(id = "malformed-id", name = "malformed", description = "Malformed schema",
            executor = TestExecutor.class, parametersJsonSchema = "{\"type\":\"object\",}")
    static class MalformedSchema {
    }

    @Tool(id = "array-id", name = "array", description = "Wrong root",
            executor = TestExecutor.class, parametersJsonSchema = "[]")
    static class ArraySchema {
    }

    @Tool(id = "trailing-id", name = "trailing", description = "Trailing content",
            executor = TestExecutor.class, parametersJsonSchema = "{\"type\":\"object\"} true")
    static class TrailingSchema {
    }

    @Tool(id = "mixed-props", name = "mixed_props", description = "Ambiguous schema",
            executor = TestExecutor.class, parametersJsonSchema = "{}",
            props = @ToolProperty(key = "path", value = @ToolArgument))
    static class MixedProperties {
    }

    @Tool(id = "mixed-extras", name = "mixed_extras", description = "Ambiguous extras",
            executor = TestExecutor.class, parametersJsonSchema = "{}",
            others = @OtherProperty(key = "title", value = "Ignored"))
    static class MixedExtras {
    }

    public static class TestExecutor implements ToolExecutor {
        @Override
        public ToolExecuteResult execute(ToolExecution execution) {
            return ToolExecuteResult.success("managed executor");
        }
    }
}
