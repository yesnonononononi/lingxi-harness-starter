package com.summit.core.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.summit.core.anno.OtherProperty;
import com.summit.core.anno.Tool;
import com.summit.core.anno.ToolProperty;

import java.util.Objects;

public final class AnnotationToolDefinitionFactory {
    private final ObjectMapper objectMapper;

    public AnnotationToolDefinitionFactory(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper cannot be null");
    }

    public <T extends ToolExecutor> ToolDefinition<T> create(Tool tool, T executor) {
        Objects.requireNonNull(tool, "tool annotation cannot be null");
        return ToolDefinition.<T>builder()
                .id(tool.id()).name(tool.name()).description(tool.description())
                .executor(executor).timeout(tool.timeout()).maxOutput(tool.maxOutput())
                .concurrentPolicy(tool.concurrentPolicy())
                .parametersJsonSchema(buildSchema(tool).toString())
                .build();
    }

    private JsonNode buildSchema(Tool tool) {
        if (!tool.parametersJsonSchema().isBlank()) {
            // Two schema sources would make an ignored constraint look successfully declared.
            if (tool.props().length != 0 || tool.others().length != 0) {
                throw new IllegalArgumentException("Tool '" + tool.id()
                        + "' cannot combine parametersJsonSchema with props or others");
            }
            try {
                JsonNode schema = objectMapper.reader()
                        .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                        .readTree(tool.parametersJsonSchema());
                if (schema == null || !schema.isObject()) {
                    throw new IllegalArgumentException("Tool '" + tool.id() + "' parameter schema must be a JSON object");
                }
                return schema;
            } catch (JsonProcessingException error) {
                throw new IllegalArgumentException("Tool '" + tool.id() + "' parameter schema is not valid JSON", error);
            }
        }

        ObjectNode schema = objectMapper.createObjectNode();
        ObjectNode properties = objectMapper.createObjectNode();
        ArrayNode required = objectMapper.createArrayNode();
        for (ToolProperty property : tool.props()) {
            ObjectNode argument = properties.putObject(property.key());
            argument.put("type", property.value().type());
            argument.put("description", property.value().description());
            if (property.value().isRequired()) {
                required.add(property.key());
            }
        }
        schema.put("type", tool.type());
        schema.set("properties", properties);
        schema.set("required", required);
        for (OtherProperty property : tool.others()) {
            schema.put(property.key(), property.value());
        }
        return schema;
    }
}
