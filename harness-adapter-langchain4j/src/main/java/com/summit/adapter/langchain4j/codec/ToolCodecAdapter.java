package com.summit.adapter.langchain4j.codec;

import com.summit.harnesscore.adapter.ToolCodec;
import com.summit.harnesscore.tool.ToolDefinition;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;

/**
 * Codec between the framework-neutral core {@link ToolDefinition} (name/description/parametersJsonSchema)
 * and the langchain4j {@link ToolSpecification}.
 */
public class ToolCodecAdapter implements ToolCodec<ToolSpecification> {

    @Override
    public ToolSpecification toFrameworkTool(ToolDefinition<?> toolDefinition) {
        JsonObjectSchema parameters = JsonSchemaConverter.convert(toolDefinition.parametersJsonSchema());
        ToolSpecification.Builder builder = ToolSpecification.builder()
                .name(toolDefinition.name());
        if (toolDefinition.description() != null) {
            builder.description(toolDefinition.description());
        }
        if (parameters != null) {
            builder.parameters(parameters);
        }
        return builder.build();
    }
}
