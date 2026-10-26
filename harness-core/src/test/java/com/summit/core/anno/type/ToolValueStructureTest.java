package com.summit.core.anno.type;

import org.junit.jupiter.api.Test;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class ToolValueStructureTest {

    @Test
    void requiresExplicitValueKindsAndPropertyIdentity() throws Exception {
        assertNull(Value.class.getDeclaredMethod("type").getDefaultValue());
        assertNull(ToolProperty.class.getDeclaredMethod("key").getDefaultValue());
        assertNull(ToolProperty.class.getDeclaredMethod("value").getDefaultValue());
        assertEquals(Set.of("STRING", "INTEGER", "NUMBER", "BOOLEAN", "ARRAY"),
                Arrays.stream(ValueType.values()).map(Enum::name).collect(Collectors.toSet()));
        assertEquals(Set.of("STRING", "INTEGER", "NUMBER", "BOOLEAN"),
                Arrays.stream(ScalarType.values()).map(Enum::name).collect(Collectors.toSet()));
    }

    @Test
    void distinguishesOmittedAllowedAndRejectedRootPolicies() {
        ObjectValue unspecified = resolveRoot(EmptyRoot.class);
        ObjectValue allowed = resolveRoot(OpenRoot.class);
        ObjectValue closed = resolveRoot(ClosedRoot.class);

        assertEquals(OptionalBoolean.UNSET, unspecified.additionalProperties());
        assertEquals(OptionalBoolean.TRUE, allowed.additionalProperties());
        assertEquals(OptionalBoolean.FALSE, closed.additionalProperties());
        assertEquals(0, unspecified.properties().length);
        assertEquals(0, unspecified.required().length);
        assertArrayEquals(new String[]{"path"}, closed.required());
        assertEquals("path", closed.properties()[0].key());
        assertEquals(ValueType.STRING, closed.properties()[0].value().type());
    }

    @Test
    void supportsStringEnumsBoundsAndEmptyDefaults() throws Exception {
        StringValue action = resolveProperty(StringFields.class, "action").stringValue();
        StringValue empty = resolveProperty(StringFields.class, "empty").stringValue();
        StringValue defaults = (StringValue) Value.class.getDeclaredMethod("stringValue").getDefaultValue();

        assertArrayEquals(new String[]{"REPLACE", "DELETE"}, action.enums());
        assertEquals(0, action.minLength());
        assertEquals(16, action.maxLength());
        assertEquals("^[A-Z]+$", action.pattern());
        assertArrayEquals(new String[]{"REPLACE"}, action.defaultValue());
        assertArrayEquals(new String[]{"REPLACE"}, action.constValue());
        assertEquals("email", resolveProperty(StringFields.class, "email").stringValue().format());
        assertArrayEquals(new String[]{""}, empty.defaultValue());
        assertArrayEquals(new String[]{""}, empty.constValue());
        assertEquals(0, defaults.defaultValue().length);
        assertEquals(0, defaults.constValue().length);
        assertEquals(-1, defaults.minLength());
        assertEquals(-1, defaults.maxLength());
        assertEquals("", defaults.pattern());
        assertEquals("", defaults.format());
    }

    @Test
    void preservesAllThreeBooleanStates() throws Exception {
        BooleanValue background = resolveProperty(BooleanFields.class, "background").booleanValue();
        BooleanValue constant = resolveProperty(BooleanFields.class, "constant").booleanValue();
        BooleanValue defaults = (BooleanValue) Value.class.getDeclaredMethod("booleanValue").getDefaultValue();

        assertEquals(OptionalBoolean.FALSE, background.defaultValue());
        assertEquals(OptionalBoolean.UNSET, background.constValue());
        assertEquals(OptionalBoolean.TRUE, constant.defaultValue());
        assertEquals(OptionalBoolean.TRUE, constant.constValue());
        assertEquals(OptionalBoolean.UNSET, defaults.defaultValue());
        assertEquals(OptionalBoolean.UNSET, defaults.constValue());
    }

    @Test
    void preservesZeroAndLongLimitsWithoutSentinels() throws Exception {
        IntegerValue zero = resolveProperty(IntegerFields.class, "zero").integerValue();
        IntegerValue extremes = resolveProperty(IntegerFields.class, "extremes").integerValue();
        IntegerValue defaults = (IntegerValue) Value.class.getDeclaredMethod("integerValue").getDefaultValue();

        assertArrayEquals(new long[]{0}, zero.minimum());
        assertArrayEquals(new long[]{Long.MAX_VALUE}, zero.maximum());
        assertArrayEquals(new long[]{-1}, zero.exclusiveMinimum());
        assertArrayEquals(new long[]{Long.MAX_VALUE}, zero.exclusiveMaximum());
        assertArrayEquals(new long[]{1}, zero.multiple());
        assertArrayEquals(new long[]{0}, zero.defaultValue());
        assertArrayEquals(new long[]{0}, zero.constValue());
        assertArrayEquals(new long[]{0, 1}, zero.enums());
        assertArrayEquals(new long[]{Long.MIN_VALUE}, extremes.minimum());
        assertArrayEquals(new long[]{Long.MAX_VALUE}, extremes.maximum());
        assertEquals(0, defaults.minimum().length);
        assertEquals(0, defaults.maximum().length);
        assertEquals(0, defaults.exclusiveMinimum().length);
        assertEquals(0, defaults.exclusiveMaximum().length);
        assertEquals(0, defaults.multiple().length);
        assertEquals(0, defaults.defaultValue().length);
        assertEquals(0, defaults.constValue().length);
        assertEquals(0, defaults.enums().length);
    }

    @Test
    void preservesFractionalLimitsAndPreviouslyReservedValues() throws Exception {
        NumberValue ratio = resolveProperty(NumberFields.class, "ratio").numberValue();
        NumberValue negative = resolveProperty(NumberFields.class, "negative").numberValue();
        NumberValue defaults = (NumberValue) Value.class.getDeclaredMethod("numberValue").getDefaultValue();

        assertArrayEquals(new double[]{0}, ratio.minimum());
        assertArrayEquals(new double[]{1}, ratio.maximum());
        assertArrayEquals(new double[]{-1}, ratio.exclusiveMinimum());
        assertArrayEquals(new double[]{2}, ratio.exclusiveMaximum());
        assertArrayEquals(new double[]{0.25}, ratio.multiple());
        assertArrayEquals(new double[]{0}, ratio.defaultValue());
        assertArrayEquals(new double[]{0}, ratio.constValue());
        assertArrayEquals(new double[]{0, 0.25}, ratio.enums());
        assertArrayEquals(new double[]{-99999999999.9}, negative.minimum());
        assertArrayEquals(new double[]{-99999999999.9}, negative.defaultValue());
        assertEquals(0, defaults.minimum().length);
        assertEquals(0, defaults.maximum().length);
        assertEquals(0, defaults.exclusiveMinimum().length);
        assertEquals(0, defaults.exclusiveMaximum().length);
        assertEquals(0, defaults.multiple().length);
        assertEquals(0, defaults.defaultValue().length);
        assertEquals(0, defaults.constValue().length);
        assertEquals(0, defaults.enums().length);
    }

    @Test
    void declaresScalarArrayItemsAndOptionalBounds() {
        ArrayValue options = resolveProperty(ArrayFields.class, "options").arrayValue();
        ArrayValue numbers = resolveProperty(ArrayFields.class, "numbers").arrayValue();

        assertEquals(ScalarType.STRING, options.itemType());
        assertEquals(-1, options.minItems());
        assertEquals(-1, options.maxItems());
        assertEquals(OptionalBoolean.UNSET, options.uniqueItems());
        assertEquals(ScalarType.INTEGER, numbers.itemType());
        assertEquals(0, numbers.minItems());
        assertEquals(4, numbers.maxItems());
        assertEquals(OptionalBoolean.FALSE, numbers.uniqueItems());
        assertEquals(ScalarType.NUMBER, resolveProperty(ArrayFields.class, "fractions").arrayValue().itemType());
        assertEquals(OptionalBoolean.TRUE, resolveProperty(ArrayFields.class, "fractions").arrayValue().uniqueItems());
        assertEquals(ScalarType.BOOLEAN, resolveProperty(ArrayFields.class, "flags").arrayValue().itemType());
        assertEquals("Available answers", resolveProperty(ArrayFields.class, "options").description());
    }

    @Test
    void retainsNestedDeclarationsAtRuntime() {
        for (Class<?> declaration : new Class<?>[]{ObjectValue.class, ToolProperty.class, Value.class,
                StringValue.class, IntegerValue.class, NumberValue.class, BooleanValue.class, ArrayValue.class}) {
            assertEquals(RetentionPolicy.RUNTIME, declaration.getAnnotation(Retention.class).value(), declaration.getName());
            assertEquals(0, declaration.getAnnotation(Target.class).value().length, declaration.getName());
        }
    }

    private ObjectValue resolveRoot(Class<?> fixture) {
        Root declaration = fixture.getAnnotation(Root.class);
        assertNotNull(declaration);
        return declaration.value();
    }

    private Value resolveProperty(Class<?> fixture, String key) {
        return Arrays.stream(resolveRoot(fixture).properties())
                .filter(property -> property.key().equals(key))
                .findFirst().orElseThrow().value();
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE)
    private @interface Root {
        ObjectValue value();
    }

    @Root(@ObjectValue)
    private static class EmptyRoot {
    }

    @Root(@ObjectValue(additionalProperties = OptionalBoolean.TRUE))
    private static class OpenRoot {
    }

    @Root(@ObjectValue(properties = @ToolProperty(key = "path", value = @Value(type = ValueType.STRING)),
            required = "path", additionalProperties = OptionalBoolean.FALSE))
    private static class ClosedRoot {
    }

    @Root(@ObjectValue(properties = {
            @ToolProperty(key = "action", value = @Value(type = ValueType.STRING,
                    stringValue = @StringValue(enums = {"REPLACE", "DELETE"}, minLength = 0, maxLength = 16,
                            pattern = "^[A-Z]+$", defaultValue = "REPLACE", constValue = "REPLACE"))),
            @ToolProperty(key = "empty", value = @Value(type = ValueType.STRING,
                    stringValue = @StringValue(defaultValue = "", constValue = ""))),
            @ToolProperty(key = "email", value = @Value(type = ValueType.STRING,
                    stringValue = @StringValue(format = "email")))
    }))
    private static class StringFields {
    }

    @Root(@ObjectValue(properties = {
            @ToolProperty(key = "background", value = @Value(type = ValueType.BOOLEAN,
                    booleanValue = @BooleanValue(defaultValue = OptionalBoolean.FALSE))),
            @ToolProperty(key = "constant", value = @Value(type = ValueType.BOOLEAN,
                    booleanValue = @BooleanValue(defaultValue = OptionalBoolean.TRUE, constValue = OptionalBoolean.TRUE)))
    }))
    private static class BooleanFields {
    }

    @Root(@ObjectValue(properties = {
            @ToolProperty(key = "zero", value = @Value(type = ValueType.INTEGER,
                    integerValue = @IntegerValue(minimum = 0, maximum = Long.MAX_VALUE,
                            exclusiveMinimum = -1, exclusiveMaximum = Long.MAX_VALUE,
                            multiple = 1, defaultValue = 0, constValue = 0, enums = {0, 1}))),
            @ToolProperty(key = "extremes", value = @Value(type = ValueType.INTEGER,
                    integerValue = @IntegerValue(minimum = Long.MIN_VALUE, maximum = Long.MAX_VALUE)))
    }))
    private static class IntegerFields {
    }

    @Root(@ObjectValue(properties = {
            @ToolProperty(key = "ratio", value = @Value(type = ValueType.NUMBER,
                    numberValue = @NumberValue(minimum = 0, maximum = 1,
                            exclusiveMinimum = -1, exclusiveMaximum = 2,
                            multiple = 0.25, defaultValue = 0, constValue = 0, enums = {0, 0.25}))),
            @ToolProperty(key = "negative", value = @Value(type = ValueType.NUMBER,
                    numberValue = @NumberValue(minimum = -99999999999.9, defaultValue = -99999999999.9)))
    }))
    private static class NumberFields {
    }

    @Root(@ObjectValue(properties = {
            @ToolProperty(key = "options", value = @Value(type = ValueType.ARRAY, description = "Available answers")),
            @ToolProperty(key = "numbers", value = @Value(type = ValueType.ARRAY,
                    arrayValue = @ArrayValue(itemType = ScalarType.INTEGER, minItems = 0,
                            maxItems = 4, uniqueItems = OptionalBoolean.FALSE))),
            @ToolProperty(key = "fractions", value = @Value(type = ValueType.ARRAY,
                    arrayValue = @ArrayValue(itemType = ScalarType.NUMBER, uniqueItems = OptionalBoolean.TRUE))),
            @ToolProperty(key = "flags", value = @Value(type = ValueType.ARRAY,
                    arrayValue = @ArrayValue(itemType = ScalarType.BOOLEAN)))
    }))
    private static class ArrayFields {
    }
}
