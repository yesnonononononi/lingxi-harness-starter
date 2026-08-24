package com.summit.harness.springbootautoconfigure.conf;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.summit.harnesscore.tool.Tool;
import com.summit.harnesscore.tool.ToolRegistry;
import com.summit.tools.file.edit.EditFileToolConfig;
import com.summit.tools.file.edit.EditFileToolDefinition;
import com.summit.tools.file.read.ReadFileToolDefinition;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

import java.util.List;

@AutoConfiguration
public class FileToolAutoConfiguration {
    @Bean
    @ConditionalOnProperty(
            prefix = "lingxi.agent.runtime.tool.edit_file",
            name = "enabled",
            havingValue = "true"
    )
    public Tool editFileToolDefinition(ObjectMapper objectMapper, ToolRegistry toolRegistry) {
        EditFileToolDefinition editFileToolDefinition = new EditFileToolDefinition(objectMapper,
                EditFileToolConfig.builder()
                        .aroundLines(3)
                        .build()
        );
        ToolSpecification toolSpec = ToolSpecification.builder()
                .name(editFileToolDefinition.name())
                .description("Edit file content")
                .parameters(JsonObjectSchema.builder()
                        .addEnumProperty("type", List.of("INSERT_BEFORE", "INSERT_AFTER", "REPLACE", "DELETE"),"INSERT_* : insert content behind or in front of anchor. ")
                        .addStringProperty("anchor", "The anchor of Insert operation it is a required parameter if type in terms of INSERT unless want to insert a empty file")
                        .addStringProperty("path", "File path relative to the workspace root. Do not use absolute paths it is a required parameter")
                        .addStringProperty("oldText", "Old text to be replaced it is a optional parameter")
                        .addStringProperty("newText", "New text to replace old text.it is empty when type is DELETE .it is a optional parameter")
                        .build())
                .build();
        toolRegistry.register(toolSpec, editFileToolDefinition);
        return editFileToolDefinition;
    }

    @Bean
    @ConditionalOnProperty(
            prefix = "lingxi.agent.runtime.tool.read_file",
            name = "enabled",
            havingValue = "true"
    )
    public Tool readFileToolDefinition(ObjectMapper objectMapper, ToolRegistry toolRegistry) {
        ReadFileToolDefinition readFileToolDefinition = new ReadFileToolDefinition(objectMapper);
        ToolSpecification toolSpec = ToolSpecification.builder()
                .name("read_file")
                .description("Read file content")
                .parameters(JsonObjectSchema.builder()
                        .addStringProperty("path", "File path it is a required parameter")
                        .build())
                .build();
        toolRegistry.register(toolSpec, readFileToolDefinition);
        return readFileToolDefinition;
    }
}
