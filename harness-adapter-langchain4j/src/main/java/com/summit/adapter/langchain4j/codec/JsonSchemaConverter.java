package com.summit.adapter.langchain4j.codec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.request.json.JsonAnyOfSchema;
import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonBooleanSchema;
import dev.langchain4j.model.chat.request.json.JsonEnumSchema;
import dev.langchain4j.model.chat.request.json.JsonIntegerSchema;
import dev.langchain4j.model.chat.request.json.JsonNumberSchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonSchemaElement;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;

import java.util.ArrayList;
import java.util.List;

/**
 * Converts a standard JSON Schema (string) into a langchain4j {@link JsonObjectSchema}.
 * Covers the common subset of tool parameter types: object/string/integer/number/boolean/enum/array/anyOf.
 */
public final class JsonSchemaConverter {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private JsonSchemaConverter() {
    }

    public static JsonObjectSchema convert(String jsonSchema) {
        if (jsonSchema == null || jsonSchema.isBlank()) {
            return null;
        }
        try {
            JsonNode root = OBJECT_MAPPER.readTree(jsonSchema);
            JsonSchemaElement element = toElement(root);
            if (element instanceof JsonObjectSchema objectSchema) {
                return objectSchema;
            }
            // fall back to an empty object when the root is not an object, to avoid rejection by the model side
            return JsonObjectSchema.builder().build();
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid tool parameters JSON schema: " + jsonSchema, e);
        }
    }

    private static JsonSchemaElement toElement(JsonNode node) {
        JsonNode type = node.get("type");
        String description = node.has("description") ? node.get("description").asText() : null;

        if (type == null && node.has("properties")) {
            type = OBJECT_MAPPER.getNodeFactory().textNode("object");
        }
        if (node.has("enum")) {
            List<String> values = new ArrayList<>();
            node.get("enum").forEach(v -> values.add(v.asText()));
            JsonEnumSchema.Builder builder = JsonEnumSchema.builder().enumValues(values);
            if (description != null) builder.description(description);
            return builder.build();
        }
        if (node.has("anyOf")) {
            List<JsonSchemaElement> options = new ArrayList<>();
            node.get("anyOf").forEach(v -> options.add(toElement(v)));
            JsonAnyOfSchema.Builder builder = JsonAnyOfSchema.builder().anyOf(options);
            if (description != null) builder.description(description);
            return builder.build();
        }

        String typeText = type == null ? "string" : type.asText();
        return switch (typeText) {
            case "object" -> toObjectSchema(node, description);
            case "integer" -> {
                JsonIntegerSchema.Builder builder = JsonIntegerSchema.builder();
                if (description != null) builder.description(description);
                yield builder.build();
            }
            case "number" -> {
                JsonNumberSchema.Builder builder = JsonNumberSchema.builder();
                if (description != null) builder.description(description);
                yield builder.build();
            }
            case "boolean" -> {
                JsonBooleanSchema.Builder builder = JsonBooleanSchema.builder();
                if (description != null) builder.description(description);
                yield builder.build();
            }
            case "array" -> {
                JsonArraySchema.Builder builder = JsonArraySchema.builder();
                if (node.has("items")) {
                    builder.items(toElement(node.get("items")));
                }
                if (description != null) builder.description(description);
                yield builder.build();
            }
            case "null" -> JsonObjectSchema.builder().build();
            default -> {
                JsonStringSchema.Builder builder = JsonStringSchema.builder();
                if (description != null) builder.description(description);
                yield builder.build();
            }
        };
    }

    private static JsonObjectSchema toObjectSchema(JsonNode node, String description) {
        JsonObjectSchema.Builder builder = JsonObjectSchema.builder();
        if (description != null) {
            builder.description(description);
        }
        JsonNode properties = node.get("properties");
        if (properties != null && properties.isObject()) {
            properties.fieldNames().forEachRemaining(name -> builder.addProperty(name, toElement(properties.get(name))));
        }
        if (node.has("required") && node.get("required").isArray()) {
            List<String> required = new ArrayList<>();
            node.get("required").forEach(v -> required.add(v.asText()));
            builder.required(required);
        }
        return builder.build();
    }
}
