package com.summit.harness.springbootautoconfigure.conf.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.summit.core.tool.diff.Differ;
import com.summit.core.tool.diff.FileHasher;
import com.summit.core.tool.diff.FileRecordManager;
import com.summit.core.tool.diff.FileRecordStore;
import com.summit.harness.springbootautoconfigure.properties.tool.CommonToolProperties;
import com.summit.harness.springbootautoconfigure.properties.tool.EditFileProperties;
import com.summit.harness.springbootautoconfigure.properties.tool.ReadFileProperties;
import com.summit.core.conversation.event.RuntimeEventPublisher;
import com.summit.core.tool.*;
import com.summit.runtime.utils.DefaultFileHasher;
import com.summit.runtime.toolSupport.DefaultFileRecordManager;
import com.summit.runtime.toolSupport.DefaultFileRecordStore;
import com.summit.runtime.toolSupport.FileRecordRestorer;
import com.summit.tools.file.edit.EditDiffer;
import com.summit.tools.file.edit.EditFileToolExecutor;
import com.summit.tools.file.read.ReadFileToolExecutor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import java.util.Objects;

@EnableConfigurationProperties({EditFileProperties.class, ReadFileProperties.class})
@AutoConfiguration
public class FileToolAutoConfiguration {
    @Bean
    @ConditionalOnProperty(
            prefix = "lingxi.agent.runtime.tool.edit-file",
            name = "enabled",
            havingValue = "true"
    )
    public ToolDefinition<EditFileToolExecutor> editFileToolDefinition(ObjectMapper objectMapper, EditFileProperties editFileProperties, CommonToolProperties commonToolProperties, RuntimeEventPublisher runtimeEventPublisher, FileRecordManager fileRecordManager) {
        String name = "edit_file";
        return ToolDefinition.<EditFileToolExecutor>builder()
                .executor(new EditFileToolExecutor(objectMapper, differ(), fileRecordManager, runtimeEventPublisher))
                .id(name)
                .name(name)
                .description("Edit file content using REPLACE, INSERT_BEFORE, INSERT_AFTER or DELETE, and return the applied diff.")
                .parametersJsonSchema("""
                        {
                          "type": "object",
                          "properties": {
                            "type": {"type": "string", "enum": ["INSERT_BEFORE", "INSERT_AFTER", "REPLACE", "DELETE"], "description": "INSERT_* : insert content behind or in front of anchor. "},
                            "anchor": {"type": "string", "description": "The anchor of Insert operation it is a required parameter if type in terms of INSERT unless want to insert a empty file"},
                            "path": {"type": "string", "description": "File path relative to the workspace root. Do not use absolute paths it is a required parameter"},
                            "oldText": {"type": "string", "description": "Old text to be replaced it is a optional parameter"},
                            "newText": {"type": "string", "description": "New text to replace old text.it is empty when type is DELETE .it is a optional parameter"}
                          }
                        }
                        """)
                .maxOutput(Objects.requireNonNullElseGet(editFileProperties.getMaxOutput(), commonToolProperties::getMaxOutput))
                .timeout(Objects.requireNonNullElseGet(editFileProperties.getTimeout(), commonToolProperties::getTimeout))
                .build();
    }

    @Bean
    @ConditionalOnProperty(
            prefix = "lingxi.agent.runtime.tool.read-file",
            name = "enabled",
            havingValue = "true"
    )
    public ToolDefinition<ReadFileToolExecutor> readFileToolDefinition(ObjectMapper objectMapper, ReadFileProperties readFileProperties, CommonToolProperties commonToolProperties) {
        String name = "read_file";
        return ToolDefinition.<ReadFileToolExecutor>builder()
                .executor(new ReadFileToolExecutor(objectMapper))
                .id(name)
                .name(name)
                .description("Read file content, optionally limited to a startLine/endLine range.")
                .parametersJsonSchema("""
                        {
                          "type": "object",
                          "properties": {
                            "path": {"type": "string", "description": "File path it is a required parameter"},
                            "startLine": {"type": "integer", "description": "Start line to read If both startLine and endLine are empty, read the entire file. start at zero"},
                            "endLine": {"type": "integer", "description": "End line to read If both startLine and endLine are empty, read the entire file"}
                          }
                        }
                        """)
                .readOnly(true)
                .maxOutput(Objects.requireNonNullElseGet(readFileProperties.getMaxOutput(), commonToolProperties::getMaxOutput))
                .timeout(Objects.requireNonNullElseGet(readFileProperties.getTimeout(), commonToolProperties::getTimeout))
                .build();
    }


    @Bean
    @ConditionalOnMissingBean
    public Differ differ(){
        return new EditDiffer();
    }


    @Bean
    @ConditionalOnMissingBean
    public FileRecordStore fileRecordStore(){
        return new DefaultFileRecordStore();
    }

    @Bean
    @ConditionalOnMissingBean
    public FileHasher fileHasher() {
        return new DefaultFileHasher();
    }

    @Bean
    @ConditionalOnMissingBean
    public FileRecordManager fileRecordManager(FileRecordStore fileRecordStore, FileHasher fileHasher) {
        return new DefaultFileRecordManager(fileRecordStore, new FileRecordRestorer(fileHasher), fileHasher);
    }

}
