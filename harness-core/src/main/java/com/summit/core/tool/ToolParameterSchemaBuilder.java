package com.summit.core.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.summit.core.anno.type.ArrayValue;
import com.summit.core.anno.type.BooleanValue;
import com.summit.core.anno.type.IntegerValue;
import com.summit.core.anno.type.NumberValue;
import com.summit.core.anno.type.ObjectValue;
import com.summit.core.anno.type.OptionalBoolean;
import com.summit.core.anno.type.StringValue;
import com.summit.core.anno.type.ToolProperty;
import com.summit.core.anno.type.Value;

import java.lang.annotation.Annotation;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Rejects ambiguous declarations before they can silently lose constraints. */
final class ToolParameterSchemaBuilder {
    private final ObjectMapper mapper;
    private final String toolId;

    ToolParameterSchemaBuilder(ObjectMapper mapper, String toolId) {
        this.mapper = mapper;
        this.toolId = toolId;
    }

    ObjectNode build(ObjectValue root) {
        ObjectNode schema = mapper.createObjectNode().put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        Set<String> names = new HashSet<>();
        for (ToolProperty property : root.properties()) {
            check(!property.key().isBlank() && names.add(property.key()), "parameters", "property keys must be nonblank and unique");
            properties.set(property.key(), buildValue(property.value(), "parameters." + property.key()));
        }
        if (root.required().length != 0) {
            ArrayNode required = schema.putArray("required");
            Set<String> requiredNames = new HashSet<>();
            for (String name : root.required()) {
                check(names.contains(name) && requiredNames.add(name), "parameters.required", "names must be declared and unique");
                required.add(name);
            }
        }
        putBoolean(schema, "additionalProperties", root.additionalProperties());
        return schema;
    }

    private ObjectNode buildValue(Value value, String path) {
        ObjectNode schema = mapper.createObjectNode().put("type", value.type().name().toLowerCase(Locale.ROOT));
        if (!value.description().isEmpty()) {
            schema.put("description", value.description());
        }
        checkInactive(value.stringValue(), "STRING", value, path);
        checkInactive(value.integerValue(), "INTEGER", value, path);
        checkInactive(value.numberValue(), "NUMBER", value, path);
        checkInactive(value.booleanValue(), "BOOLEAN", value, path);
        checkInactive(value.arrayValue(), "ARRAY", value, path);
        switch (value.type()) {
            case STRING -> buildString(schema, value.stringValue(), path);
            case INTEGER -> buildInteger(schema, value.integerValue(), path);
            case NUMBER -> buildNumber(schema, value.numberValue(), path);
            case BOOLEAN -> {
                BooleanValue constraints = value.booleanValue();
                putBoolean(schema, "default", constraints.defaultValue());
                putBoolean(schema, "const", constraints.constValue());
            }
            case ARRAY -> {
                ArrayValue constraints = value.arrayValue();
                schema.putObject("items").put("type", constraints.itemType().name().toLowerCase(Locale.ROOT));
                putLimits(schema, "minItems", constraints.minItems(), "maxItems", constraints.maxItems(), path);
                putBoolean(schema, "uniqueItems", constraints.uniqueItems());
            }
        }
        return schema;
    }

    private void checkInactive(Annotation constraints, String activeType, Value value, String path) {
        if (value.type().name().equals(activeType)) {
            return;
        }
        String member = activeType.toLowerCase(Locale.ROOT) + "Value";
        try {
            check(constraints.equals(Value.class.getDeclaredMethod(member).getDefaultValue()), path,
                    member + " must remain at its defaults when type is " + value.type());
        } catch (NoSuchMethodException error) {
            throw new IllegalStateException(error);
        }
    }

    private void buildString(ObjectNode schema, StringValue value, String path) {
        putLimits(schema, "minLength", value.minLength(), "maxLength", value.maxLength(), path);
        if (!value.pattern().isEmpty()) {
            schema.put("pattern", value.pattern());
        }
        if (!value.format().isEmpty()) {
            schema.put("format", value.format());
        }
        check(value.defaultValue().length <= 1 && value.constValue().length <= 1, path, "default and const accept at most one value");
        if (value.defaultValue().length == 1) {
            schema.put("default", value.defaultValue()[0]);
        }
        if (value.constValue().length == 1) {
            schema.put("const", value.constValue()[0]);
        }
        if (value.enums().length != 0) {
            ArrayNode values = schema.putArray("enum");
            Set<String> unique = new HashSet<>();
            for (String entry : value.enums()) {
                check(unique.add(entry), path + ".enum", "values must be unique");
                values.add(entry);
            }
        }
    }

    private void buildInteger(ObjectNode schema, IntegerValue value, String path) {
        putLong(schema, "minimum", value.minimum(), path);
        putLong(schema, "maximum", value.maximum(), path);
        putLong(schema, "exclusiveMinimum", value.exclusiveMinimum(), path);
        putLong(schema, "exclusiveMaximum", value.exclusiveMaximum(), path);
        putLong(schema, "multipleOf", value.multiple(), path);
        putLong(schema, "default", value.defaultValue(), path);
        putLong(schema, "const", value.constValue(), path);
        if (value.enums().length != 0) {
            ArrayNode values = schema.putArray("enum");
            Set<Long> unique = new HashSet<>();
            for (long entry : value.enums()) {
                check(unique.add(entry), path + ".enum", "values must be unique");
                values.add(entry);
            }
        }
        checkNumericRange(schema, path);
    }

    private void buildNumber(ObjectNode schema, NumberValue value, String path) {
        putDouble(schema, "minimum", value.minimum(), path);
        putDouble(schema, "maximum", value.maximum(), path);
        putDouble(schema, "exclusiveMinimum", value.exclusiveMinimum(), path);
        putDouble(schema, "exclusiveMaximum", value.exclusiveMaximum(), path);
        putDouble(schema, "multipleOf", value.multiple(), path);
        putDouble(schema, "default", value.defaultValue(), path);
        putDouble(schema, "const", value.constValue(), path);
        if (value.enums().length != 0) {
            ArrayNode values = schema.putArray("enum");
            Set<BigDecimal> unique = new HashSet<>();
            for (double entry : value.enums()) {
                check(Double.isFinite(entry), path + ".enum", "numbers must be finite");
                check(unique.add(BigDecimal.valueOf(entry).stripTrailingZeros()), path + ".enum", "values must be unique");
                values.add(entry);
            }
        }
        checkNumericRange(schema, path);
    }

    private void putLong(ObjectNode schema, String keyword, long[] values, String path) {
        check(values.length <= 1, path + "." + keyword, "accepts at most one value");
        if (values.length == 1) {
            schema.put(keyword, values[0]);
        }
    }

    private void putDouble(ObjectNode schema, String keyword, double[] values, String path) {
        check(values.length <= 1, path + "." + keyword, "accepts at most one value");
        if (values.length == 1) {
            check(Double.isFinite(values[0]), path + "." + keyword, "numbers must be finite");
            schema.put(keyword, values[0]);
        }
    }

    private void checkNumericRange(ObjectNode schema, String path) {
        if (schema.has("multipleOf")) {
            check(schema.get("multipleOf").decimalValue().signum() > 0, path + ".multipleOf", "must be positive");
        }
        for (String lower : new String[]{"minimum", "exclusiveMinimum"}) {
            for (String upper : new String[]{"maximum", "exclusiveMaximum"}) {
                if (schema.has(lower) && schema.has(upper)) {
                    int comparison = schema.get(lower).decimalValue().compareTo(schema.get(upper).decimalValue());
                    check(comparison < 0 || (comparison == 0 && lower.equals("minimum") && upper.equals("maximum")),
                            path, "numeric bounds must allow a nonempty range");
                }
            }
        }
    }

    private void putLimits(ObjectNode schema, String lower, int minimum, String upper, int maximum, String path) {
        check(minimum >= -1 && maximum >= -1, path, "length and item limits must be -1 or nonnegative");
        check(minimum == -1 || maximum == -1 || minimum <= maximum, path, "minimum must not exceed maximum");
        if (minimum != -1) {
            schema.put(lower, minimum);
        }
        if (maximum != -1) {
            schema.put(upper, maximum);
        }
    }

    private void putBoolean(ObjectNode schema, String keyword, OptionalBoolean value) {
        if (value != OptionalBoolean.UNSET) {
            schema.put(keyword, value == OptionalBoolean.TRUE);
        }
    }

    private void check(boolean valid, String path, String reason) {
        if (!valid) {
            throw new IllegalArgumentException("Tool '" + toolId + "' " + path + ": " + reason);
        }
    }
}
