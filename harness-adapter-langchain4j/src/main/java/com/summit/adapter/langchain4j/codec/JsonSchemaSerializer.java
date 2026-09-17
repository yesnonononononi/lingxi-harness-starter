package com.summit.adapter.langchain4j.codec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.langchain4j.model.chat.request.json.JsonAnyOfSchema;
import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonBooleanSchema;
import dev.langchain4j.model.chat.request.json.JsonEnumSchema;
import dev.langchain4j.model.chat.request.json.JsonIntegerSchema;
import dev.langchain4j.model.chat.request.json.JsonNullSchema;
import dev.langchain4j.model.chat.request.json.JsonNumberSchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonRawSchema;
import dev.langchain4j.model.chat.request.json.JsonReferenceSchema;
import dev.langchain4j.model.chat.request.json.JsonSchemaElement;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;

/**
 * Converts a langchain4j {@link JsonSchemaElement} back into a standard JSON Schema string, the
 * neutral form core keeps in {@code ToolDefinition#parametersJsonSchema}.
 *
 * <p>Counterpart of {@link JsonSchemaConverter} and covers the same subset of types. It is needed
 * whenever a schema does not originate from a JSON string on the core side — MCP tool specifications
 * arrive as langchain4j schemas and have no other way back into the core representation.</p>
 */
public final class JsonSchemaSerializer {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private JsonSchemaSerializer() {
    }

    public static String toJson(JsonSchemaElement element) {
        if (element == null) {
            return null;
        }
        return toNode(element).toString();
    }

    private static ObjectNode toNode(JsonSchemaElement element) {
        if (element instanceof JsonRawSchema rawSchema) {
            // a raw schema already is JSON, so it is authoritative and passes through untouched
            JsonNode raw = parse(rawSchema.schema());
            if (raw instanceof ObjectNode objectNode) {
                return objectNode;
            }
        }

        ObjectNode node = OBJECT_MAPPER.createObjectNode();
        String primitiveType = primitiveTypeOf(element);
        if (primitiveType != null) {
            node.put("type", primitiveType);
        } else {
            switch (element) {
                case JsonObjectSchema objectSchema -> toObjectNode(node, objectSchema);
                case JsonArraySchema arraySchema -> {
                    node.put("type", "array");
                    if (arraySchema.items() != null) {
                        node.set("items", toNode(arraySchema.items()));
                    }
                }
                case JsonEnumSchema enumSchema -> {
                    node.put("type", "string");
                    ArrayNode values = node.putArray("enum");
                    enumSchema.enumValues().forEach(values::add);
                }
                case JsonAnyOfSchema anyOfSchema -> {
                    ArrayNode options = node.putArray("anyOf");
                    anyOfSchema.anyOf().forEach(option -> options.add(toNode(option)));
                }
                case JsonReferenceSchema referenceSchema -> node.put("$ref", referenceSchema.reference());
                default -> {
                    // unknown element type: keep an empty (thus permissive) schema instead of failing the tool
                }
            }
        }
        if (element.description() != null) {
            node.put("description", element.description());
        }
        return node;
    }

    private static void toObjectNode(ObjectNode node, JsonObjectSchema schema) {
        node.put("type", "object");
        if (schema.properties() != null && !schema.properties().isEmpty()) {
            ObjectNode properties = node.putObject("properties");
            schema.properties().forEach((name, property) -> properties.set(name, toNode(property)));
        }
        if (schema.required() != null && !schema.required().isEmpty()) {
            ArrayNode required = node.putArray("required");
            schema.required().forEach(required::add);
        }
        if (schema.additionalProperties() != null) {
            node.put("additionalProperties", schema.additionalProperties());
        }
        if (schema.definitions() != null && !schema.definitions().isEmpty()) {
            ObjectNode definitions = node.putObject("$defs");
            schema.definitions().forEach((name, definition) -> definitions.set(name, toNode(definition)));
        }
    }

    private static String primitiveTypeOf(JsonSchemaElement element) {
        if (element instanceof JsonStringSchema) {
            return "string";
        }
        if (element instanceof JsonIntegerSchema) {
            return "integer";
        }
        if (element instanceof JsonNumberSchema) {
            return "number";
        }
        if (element instanceof JsonBooleanSchema) {
            return "boolean";
        }
        if (element instanceof JsonNullSchema) {
            return "null";
        }
        return null;
    }

    private static JsonNode parse(String jsonSchema) {
        if (jsonSchema == null || jsonSchema.isBlank()) {
            return null;
        }
        try {
            return OBJECT_MAPPER.readTree(jsonSchema);
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid raw JSON schema: " + jsonSchema, e);
        }
    }
}
