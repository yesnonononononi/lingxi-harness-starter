package com.summit.harness.springbootautoconfigure.conf.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.difflib.patch.Patch;
import com.summit.harness.springbootautoconfigure.properties.tool.CommonToolProperties;
import com.summit.harness.springbootautoconfigure.properties.tool.EditFileProperties;
import com.summit.harness.springbootautoconfigure.properties.tool.ReadFileProperties;
import com.summit.harnesscore.conversation.event.RuntimeEventPublisher;
import com.summit.harnesscore.tool.Differ;
import com.summit.harnesscore.tool.PatchManager;
import com.summit.harnesscore.tool.ToolDefinition;
import com.summit.harnesscore.tool.ToolRegistry;
import com.summit.runtime.tool.DefaultPatchManager;
import com.summit.tools.file.edit.EditDiffer;
import com.summit.tools.file.edit.EditFileToolExecutor;
import com.summit.tools.file.read.ReadFileToolExecutor;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

@EnableConfigurationProperties({EditFileProperties.class, ReadFileProperties.class})
@AutoConfiguration
public class FileToolAutoConfiguration {
    @Bean
    @ConditionalOnProperty(
            prefix = "lingxi.agent.runtime.tool.edit-file",
            name = "enabled",
            havingValue = "true"
    )
    public ToolDefinition<EditFileToolExecutor> editFileToolDefinition(ObjectMapper objectMapper, ToolRegistry toolRegistry, EditFileProperties editFileProperties, CommonToolProperties commonToolProperties, RuntimeEventPublisher runtimeEventPublisher) {
        ToolSpecification toolSpec = ToolSpecification.builder()
                .name("edit_file")
                .description("Edit file content. THE ONLY tool for creating/modifying files; do NOT use terminal commands (echo/sed/redirect/Set-Content) instead. Use REPLACE to substitute oldText with newText, INSERT_BEFORE/INSERT_AFTER to insert around an anchor, DELETE to remove oldText. Returns the applied diff.")
                .parameters(JsonObjectSchema.builder()
                        .addEnumProperty("type", List.of("INSERT_BEFORE", "INSERT_AFTER", "REPLACE", "DELETE"), "INSERT_* : insert content behind or in front of anchor. ")
                        .addStringProperty("anchor", "The anchor of Insert operation it is a required parameter if type in terms of INSERT unless want to insert a empty file")
                        .addStringProperty("path", "File path relative to the workspace root. Do not use absolute paths it is a required parameter")
                        .addStringProperty("oldText", "Old text to be replaced it is a optional parameter")
                        .addStringProperty("newText", "New text to replace old text.it is empty when type is DELETE .it is a optional parameter")
                        .build())
                .build();
        ToolDefinition<EditFileToolExecutor> definition = ToolDefinition.<EditFileToolExecutor>builder()
                .executor(new EditFileToolExecutor(objectMapper, differ(), defaultPatchManager(),runtimeEventPublisher))
                .toolSpecification(toolSpec)
                .maxOutput(Objects.requireNonNullElseGet(editFileProperties.getMaxOutput(), commonToolProperties::getMaxOutput))
                .timeout(Objects.requireNonNullElseGet(editFileProperties.getTimeout(), commonToolProperties::getTimeout))
                .build();
        toolRegistry.register(toolSpec.name(), definition);
        return definition;
    }

    @Bean
    @ConditionalOnProperty(
            prefix = "lingxi.agent.runtime.tool.read-file",
            name = "enabled",
            havingValue = "true"
    )
    public ToolDefinition<ReadFileToolExecutor> readFileToolDefinition(ObjectMapper objectMapper, ToolRegistry toolRegistry, ReadFileProperties readFileProperties, CommonToolProperties commonToolProperties) {
        ToolSpecification toolSpec = ToolSpecification.builder()
                .name("read_file")
                .description("Read file content. THE ONLY tool for reading files; do NOT use terminal commands (Get-Content/cat/type) instead, their output gets truncated. For large files pass startLine/endLine to read a range ")
                .parameters(JsonObjectSchema.builder()
                        .addStringProperty("path", "File path it is a required parameter")
                        .addIntegerProperty("startLine", "Start line to read If both startLine and endLine are empty, read the entire file. start at zero")
                        .addIntegerProperty("endLine", "End line to read If both startLine and endLine are empty, read the entire file")
                        .build())
                .build();
        ToolDefinition<ReadFileToolExecutor> readFileToolDefinition = ToolDefinition.<ReadFileToolExecutor>builder()
                .executor(new ReadFileToolExecutor(objectMapper))
                .toolSpecification(toolSpec)
                .maxOutput(Objects.requireNonNullElseGet(readFileProperties.getMaxOutput(), commonToolProperties::getMaxOutput))
                .timeout(Objects.requireNonNullElseGet(readFileProperties.getTimeout(), commonToolProperties::getTimeout))
                .build();
        toolRegistry.register(toolSpec.name(), readFileToolDefinition);
        return readFileToolDefinition;
    }


    @Bean
    @ConditionalOnMissingBean
    public Differ differ(){
        return new EditDiffer();
    }

    @Bean
    @ConditionalOnMissingBean
    public PatchManager<UUID> defaultPatchManager(){
        return new DefaultPatchManager();
    }

}
