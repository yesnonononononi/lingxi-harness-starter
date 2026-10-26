package com.summit.core.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.summit.core.anno.Tool;
import com.summit.core.anno.OtherProperty;
import com.summit.core.anno.type.ArrayValue;
import com.summit.core.anno.type.BooleanValue;
import com.summit.core.anno.type.IntegerValue;
import com.summit.core.anno.type.NumberValue;
import com.summit.core.anno.type.ObjectValue;
import com.summit.core.anno.type.OptionalBoolean;
import com.summit.core.anno.type.ScalarType;
import com.summit.core.anno.type.StringValue;
import com.summit.core.anno.type.ToolProperty;
import com.summit.core.anno.type.Value;
import com.summit.core.anno.type.ValueType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class StructuredToolSchemaTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final AnnotationToolDefinitionFactory factory = new AnnotationToolDefinitionFactory(mapper);

    @Test
    void rendersAllSelectedConstraintFamiliesWithoutInventingDefaults() throws Exception {
        JsonNode schema = parse(Complete.class);
        assertEquals(mapper.readTree("""
                {"type":"object","properties":{
                  "text":{"type":"string","description":"Text","minLength":0,"maxLength":8,
                    "pattern":"[a-z]*","format":"custom","enum":["","abc"],"default":"","const":"abc"},
                  "count":{"type":"integer","minimum":0,"maximum":20,"exclusiveMinimum":-1,
                    "exclusiveMaximum":21,"multipleOf":2,"default":0,"const":2,"enum":[0,2,20]},
                  "ratio":{"type":"number","minimum":0.0,"maximum":1.0,"exclusiveMinimum":-0.5,
                    "exclusiveMaximum":1.5,"multipleOf":0.25,"default":0.0,"const":0.5,"enum":[0.0,0.5,1.0]},
                  "flag":{"type":"boolean","default":false,"const":true},
                  "items":{"type":"array","items":{"type":"integer"},"minItems":0,"maxItems":3,"uniqueItems":false},
                  "plain":{"type":"string"},"enabled":{"type":"boolean"},
                  "texts":{"type":"array","items":{"type":"string"}},
                  "numbers":{"type":"array","items":{"type":"number"},"uniqueItems":true},
                  "flags":{"type":"array","items":{"type":"boolean"}},
                  "limit":{"type":"integer","minimum":-9223372036854775808,"maximum":9223372036854775807}
                },"required":["text","count"],"additionalProperties":false}
                """), schema);
    }

    @Test
    void explicitOpenObjectAndBooleanTrueArePreserved() throws Exception {
        JsonNode schema = parse(Open.class);
        assertTrue(schema.path("additionalProperties").booleanValue());
        assertTrue(schema.at("/properties/flag/default").booleanValue());
        assertFalse(schema.at("/properties/flag/const").booleanValue());
        assertFalse(schema.has("required"));
    }

    @ParameterizedTest
    @MethodSource("invalidDeclarations")
    void invalidDeclarationsIdentifyTheirTool(Class<?> declaration) {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> parse(declaration));
        assertTrue(error.getMessage().contains(declaration.getAnnotation(Tool.class).id()));
    }

    private JsonNode parse(Class<?> declaration) throws Exception {
        ToolDefinition<AnnotationToolDefinitionFactoryTest.TestExecutor> definition = factory.create(
                declaration.getAnnotation(Tool.class), new AnnotationToolDefinitionFactoryTest.TestExecutor());
        return mapper.readTree(definition.parametersJsonSchema());
    }

    @Tool(id = "complete", name = "complete", description = "All constraints", executor = AnnotationToolDefinitionFactoryTest.TestExecutor.class,
            parameters = @ObjectValue(properties = {
                    @ToolProperty(key = "text", value = @Value(type = ValueType.STRING, description = "Text",
                            stringValue = @StringValue(minLength = 0, maxLength = 8, pattern = "[a-z]*", format = "custom",
                                    enums = {"", "abc"}, defaultValue = "", constValue = "abc"))),
                    @ToolProperty(key = "count", value = @Value(type = ValueType.INTEGER,
                            integerValue = @IntegerValue(minimum = 0, maximum = 20, exclusiveMinimum = -1,
                                    exclusiveMaximum = 21, multiple = 2, defaultValue = 0, constValue = 2, enums = {0,2,20}))),
                    @ToolProperty(key = "ratio", value = @Value(type = ValueType.NUMBER,
                            numberValue = @NumberValue(minimum = 0, maximum = 1, exclusiveMinimum = -0.5,
                                    exclusiveMaximum = 1.5, multiple = 0.25, defaultValue = 0, constValue = 0.5, enums = {0,0.5,1}))),
                    @ToolProperty(key = "flag", value = @Value(type = ValueType.BOOLEAN,
                            booleanValue = @BooleanValue(defaultValue = OptionalBoolean.FALSE, constValue = OptionalBoolean.TRUE))),
                    @ToolProperty(key = "items", value = @Value(type = ValueType.ARRAY,
                            arrayValue = @ArrayValue(itemType = ScalarType.INTEGER, minItems = 0, maxItems = 3, uniqueItems = OptionalBoolean.FALSE))),
                    @ToolProperty(key = "plain", value = @Value(type = ValueType.STRING)),
                    @ToolProperty(key = "enabled", value = @Value(type = ValueType.BOOLEAN)),
                    @ToolProperty(key = "texts", value = @Value(type = ValueType.ARRAY)),
                    @ToolProperty(key = "numbers", value = @Value(type = ValueType.ARRAY,
                            arrayValue = @ArrayValue(itemType = ScalarType.NUMBER, uniqueItems = OptionalBoolean.TRUE))),
                    @ToolProperty(key = "flags", value = @Value(type = ValueType.ARRAY, arrayValue = @ArrayValue(itemType = ScalarType.BOOLEAN))),
                    @ToolProperty(key = "limit", value = @Value(type = ValueType.INTEGER,
                            integerValue = @IntegerValue(minimum = Long.MIN_VALUE, maximum = Long.MAX_VALUE)))
            }, required = {"text", "count"}, additionalProperties = OptionalBoolean.FALSE))
    static class Complete { }

    @Tool(id = "open", name = "open", description = "Explicit true", executor = AnnotationToolDefinitionFactoryTest.TestExecutor.class,
            parameters = @ObjectValue(properties = @ToolProperty(key = "flag", value = @Value(type = ValueType.BOOLEAN,
                    booleanValue = @BooleanValue(defaultValue = OptionalBoolean.TRUE, constValue = OptionalBoolean.FALSE))),
                    additionalProperties = OptionalBoolean.TRUE))
    static class Open { }

    @Tool(id = "UnknownRequired", name = "invalid", description = "Invalid declaration", executor = AnnotationToolDefinitionFactoryTest.TestExecutor.class,
            parameters = @ObjectValue(required = "missing"))
    static class UnknownRequired { }

    @Tool(id = "DuplicateRequired", name = "invalid", description = "Invalid declaration", executor = AnnotationToolDefinitionFactoryTest.TestExecutor.class,
            parameters = @ObjectValue(properties = @ToolProperty(key = "x", value = @Value(type = ValueType.STRING)), required = {"x", "x"}))
    static class DuplicateRequired { }

    @Tool(id = "BlankKey", name = "invalid", description = "Invalid declaration", executor = AnnotationToolDefinitionFactoryTest.TestExecutor.class,
            parameters = @ObjectValue(properties = @ToolProperty(key = " ", value = @Value(type = ValueType.STRING))))
    static class BlankKey { }

    @Tool(id = "DuplicateKey", name = "invalid", description = "Invalid declaration", executor = AnnotationToolDefinitionFactoryTest.TestExecutor.class,
            parameters = @ObjectValue(properties = {@ToolProperty(key = "x", value = @Value(type = ValueType.STRING)), @ToolProperty(key = "x", value = @Value(type = ValueType.BOOLEAN))}))
    static class DuplicateKey { }

    @Tool(id = "InactiveString", name = "invalid", description = "Invalid declaration", executor = AnnotationToolDefinitionFactoryTest.TestExecutor.class,
            parameters = @ObjectValue(properties = @ToolProperty(key = "x", value = @Value(type = ValueType.INTEGER, stringValue = @StringValue(minLength = 1)))))
    static class InactiveString { }

    @Tool(id = "InactiveInteger", name = "invalid", description = "Invalid declaration", executor = AnnotationToolDefinitionFactoryTest.TestExecutor.class,
            parameters = @ObjectValue(properties = @ToolProperty(key = "x", value = @Value(type = ValueType.STRING, integerValue = @IntegerValue(minimum = 0)))))
    static class InactiveInteger { }

    @Tool(id = "InactiveNumber", name = "invalid", description = "Invalid declaration", executor = AnnotationToolDefinitionFactoryTest.TestExecutor.class,
            parameters = @ObjectValue(properties = @ToolProperty(key = "x", value = @Value(type = ValueType.STRING, numberValue = @NumberValue(minimum = 0)))))
    static class InactiveNumber { }

    @Tool(id = "InactiveBoolean", name = "invalid", description = "Invalid declaration", executor = AnnotationToolDefinitionFactoryTest.TestExecutor.class,
            parameters = @ObjectValue(properties = @ToolProperty(key = "x", value = @Value(type = ValueType.STRING, booleanValue = @BooleanValue(defaultValue = OptionalBoolean.FALSE)))))
    static class InactiveBoolean { }

    @Tool(id = "InactiveArray", name = "invalid", description = "Invalid declaration", executor = AnnotationToolDefinitionFactoryTest.TestExecutor.class,
            parameters = @ObjectValue(properties = @ToolProperty(key = "x", value = @Value(type = ValueType.STRING, arrayValue = @ArrayValue(minItems = 0)))))
    static class InactiveArray { }

    @Tool(id = "StringDefaultMany", name = "invalid", description = "Invalid declaration", executor = AnnotationToolDefinitionFactoryTest.TestExecutor.class,
            parameters = @ObjectValue(properties = @ToolProperty(key = "x", value = @Value(type = ValueType.STRING, stringValue = @StringValue(defaultValue = {"a","b"})))))
    static class StringDefaultMany { }

    @Tool(id = "StringConstMany", name = "invalid", description = "Invalid declaration", executor = AnnotationToolDefinitionFactoryTest.TestExecutor.class,
            parameters = @ObjectValue(properties = @ToolProperty(key = "x", value = @Value(type = ValueType.STRING, stringValue = @StringValue(constValue = {"a","b"})))))
    static class StringConstMany { }

    @Tool(id = "StringNegative", name = "invalid", description = "Invalid declaration", executor = AnnotationToolDefinitionFactoryTest.TestExecutor.class,
            parameters = @ObjectValue(properties = @ToolProperty(key = "x", value = @Value(type = ValueType.STRING, stringValue = @StringValue(minLength = -2)))))
    static class StringNegative { }

    @Tool(id = "StringRange", name = "invalid", description = "Invalid declaration", executor = AnnotationToolDefinitionFactoryTest.TestExecutor.class,
            parameters = @ObjectValue(properties = @ToolProperty(key = "x", value = @Value(type = ValueType.STRING, stringValue = @StringValue(minLength = 2, maxLength = 1)))))
    static class StringRange { }

    @Tool(id = "StringEnumDuplicates", name = "invalid", description = "Invalid declaration", executor = AnnotationToolDefinitionFactoryTest.TestExecutor.class,
            parameters = @ObjectValue(properties = @ToolProperty(key = "x", value = @Value(type = ValueType.STRING, stringValue = @StringValue(enums = {"a","a"})))))
    static class StringEnumDuplicates { }

    @Tool(id = "ArrayNegative", name = "invalid", description = "Invalid declaration", executor = AnnotationToolDefinitionFactoryTest.TestExecutor.class,
            parameters = @ObjectValue(properties = @ToolProperty(key = "x", value = @Value(type = ValueType.ARRAY, arrayValue = @ArrayValue(maxItems = -2)))))
    static class ArrayNegative { }

    @Tool(id = "ArrayRange", name = "invalid", description = "Invalid declaration", executor = AnnotationToolDefinitionFactoryTest.TestExecutor.class,
            parameters = @ObjectValue(properties = @ToolProperty(key = "x", value = @Value(type = ValueType.ARRAY, arrayValue = @ArrayValue(minItems = 2, maxItems = 1)))))
    static class ArrayRange { }

    @Tool(id = "IntegerMany", name = "invalid", description = "Invalid declaration", executor = AnnotationToolDefinitionFactoryTest.TestExecutor.class,
            parameters = @ObjectValue(properties = @ToolProperty(key = "x", value = @Value(type = ValueType.INTEGER, integerValue = @IntegerValue(minimum = {0,1})))))
    static class IntegerMany { }

    @Tool(id = "IntegerRange", name = "invalid", description = "Invalid declaration", executor = AnnotationToolDefinitionFactoryTest.TestExecutor.class,
            parameters = @ObjectValue(properties = @ToolProperty(key = "x", value = @Value(type = ValueType.INTEGER, integerValue = @IntegerValue(minimum = 2, maximum = 1)))))
    static class IntegerRange { }

    @Tool(id = "IntegerExclusive", name = "invalid", description = "Invalid declaration", executor = AnnotationToolDefinitionFactoryTest.TestExecutor.class,
            parameters = @ObjectValue(properties = @ToolProperty(key = "x", value = @Value(type = ValueType.INTEGER, integerValue = @IntegerValue(exclusiveMinimum = 1, maximum = 1)))))
    static class IntegerExclusive { }

    @Tool(id = "IntegerMultiple", name = "invalid", description = "Invalid declaration", executor = AnnotationToolDefinitionFactoryTest.TestExecutor.class,
            parameters = @ObjectValue(properties = @ToolProperty(key = "x", value = @Value(type = ValueType.INTEGER, integerValue = @IntegerValue(multiple = 0)))))
    static class IntegerMultiple { }

    @Tool(id = "IntegerEnumDuplicates", name = "invalid", description = "Invalid declaration", executor = AnnotationToolDefinitionFactoryTest.TestExecutor.class,
            parameters = @ObjectValue(properties = @ToolProperty(key = "x", value = @Value(type = ValueType.INTEGER, integerValue = @IntegerValue(enums = {1,1})))))
    static class IntegerEnumDuplicates { }

    @Tool(id = "NumberMany", name = "invalid", description = "Invalid declaration", executor = AnnotationToolDefinitionFactoryTest.TestExecutor.class,
            parameters = @ObjectValue(properties = @ToolProperty(key = "x", value = @Value(type = ValueType.NUMBER, numberValue = @NumberValue(defaultValue = {0,1})))))
    static class NumberMany { }

    @Tool(id = "NumberNaN", name = "invalid", description = "Invalid declaration", executor = AnnotationToolDefinitionFactoryTest.TestExecutor.class,
            parameters = @ObjectValue(properties = @ToolProperty(key = "x", value = @Value(type = ValueType.NUMBER, numberValue = @NumberValue(minimum = Double.NaN)))))
    static class NumberNaN { }

    @Tool(id = "NumberInfinite", name = "invalid", description = "Invalid declaration", executor = AnnotationToolDefinitionFactoryTest.TestExecutor.class,
            parameters = @ObjectValue(properties = @ToolProperty(key = "x", value = @Value(type = ValueType.NUMBER, numberValue = @NumberValue(enums = Double.POSITIVE_INFINITY)))))
    static class NumberInfinite { }

    @Tool(id = "NumberRange", name = "invalid", description = "Invalid declaration", executor = AnnotationToolDefinitionFactoryTest.TestExecutor.class,
            parameters = @ObjectValue(properties = @ToolProperty(key = "x", value = @Value(type = ValueType.NUMBER, numberValue = @NumberValue(minimum = 2, maximum = 1)))))
    static class NumberRange { }

    @Tool(id = "NumberExclusive", name = "invalid", description = "Invalid declaration", executor = AnnotationToolDefinitionFactoryTest.TestExecutor.class,
            parameters = @ObjectValue(properties = @ToolProperty(key = "x", value = @Value(type = ValueType.NUMBER, numberValue = @NumberValue(minimum = 1, exclusiveMaximum = 1)))))
    static class NumberExclusive { }

    @Tool(id = "NumberMultiple", name = "invalid", description = "Invalid declaration", executor = AnnotationToolDefinitionFactoryTest.TestExecutor.class,
            parameters = @ObjectValue(properties = @ToolProperty(key = "x", value = @Value(type = ValueType.NUMBER, numberValue = @NumberValue(multiple = -1)))))
    static class NumberMultiple { }

    @Tool(id = "NumberEnumDuplicates", name = "invalid", description = "Invalid declaration", executor = AnnotationToolDefinitionFactoryTest.TestExecutor.class,
            parameters = @ObjectValue(properties = @ToolProperty(key = "x", value = @Value(type = ValueType.NUMBER, numberValue = @NumberValue(enums = {0.0,-0.0})))))
    static class NumberEnumDuplicates { }

    @Tool(id = "MixedRaw", name = "mixed", description = "Mixed declaration", executor = AnnotationToolDefinitionFactoryTest.TestExecutor.class,
            parametersJsonSchema = "{}", parameters = @ObjectValue(additionalProperties = OptionalBoolean.FALSE))
    static class MixedRaw { }

    @Tool(id = "MixedLegacy", name = "mixed", description = "Mixed declaration", executor = AnnotationToolDefinitionFactoryTest.TestExecutor.class,
            type = "string", parameters = @ObjectValue(additionalProperties = OptionalBoolean.FALSE))
    static class MixedLegacy { }

    @Tool(id = "MixedType", name = "mixed", description = "Mixed declaration", executor = AnnotationToolDefinitionFactoryTest.TestExecutor.class,
            type = "array", parameters = @ObjectValue(additionalProperties = OptionalBoolean.FALSE))
    static class MixedType { }

    @Tool(id = "MixedOthers", name = "mixed", description = "Mixed declaration", executor = AnnotationToolDefinitionFactoryTest.TestExecutor.class,
            others = @OtherProperty(key = "title", value = "ignored"), parameters = @ObjectValue(additionalProperties = OptionalBoolean.FALSE))
    static class MixedOthers { }

    static Stream<Class<?>> invalidDeclarations() {
        return Stream.of(UnknownRequired.class, DuplicateRequired.class, BlankKey.class, DuplicateKey.class, InactiveString.class, InactiveInteger.class, InactiveNumber.class, InactiveBoolean.class, InactiveArray.class, StringDefaultMany.class, StringConstMany.class, StringNegative.class, StringRange.class, StringEnumDuplicates.class, ArrayNegative.class, ArrayRange.class, IntegerMany.class, IntegerRange.class, IntegerExclusive.class, IntegerMultiple.class, IntegerEnumDuplicates.class, NumberMany.class, NumberNaN.class, NumberInfinite.class, NumberRange.class, NumberExclusive.class, NumberMultiple.class, NumberEnumDuplicates.class, MixedRaw.class, MixedLegacy.class, MixedType.class, MixedOthers.class);
    }
}
