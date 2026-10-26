package com.summit.core.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.summit.core.anno.Tool;
import com.summit.core.anno.type.ObjectValue;
import com.summit.core.anno.type.OptionalBoolean;

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
        ObjectValue parameters = tool.parameters();
        if (!tool.parametersJsonSchema().isBlank()) {
            // Two schema sources would make an ignored constraint look successfully declared.
            if (parameters.properties().length != 0 || parameters.required().length != 0
                    || parameters.additionalProperties() != OptionalBoolean.UNSET) {
                throw new IllegalArgumentException("Tool '" + tool.id()
                        + "' cannot combine parametersJsonSchema with parameters");
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

        return new ToolParameterSchemaBuilder(objectMapper, tool.id()).build(parameters);
    }
}
