package com.summit.harness.springbootautoconfigure.conf;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.summit.harnesscore.tool.Tool;
import com.summit.harnesscore.tool.ToolRegistry;
import com.summit.runtime.tools.file.definition.ReadFileToolDefinition;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;


@AutoConfiguration
public class ToolConfig {

    @Bean
    public Tool readFileToolDefinition(ObjectMapper objectMapper, ToolRegistry toolRegistry){
        ReadFileToolDefinition readFileToolDefinition = new ReadFileToolDefinition(objectMapper);
        ToolSpecification toolSpec = ToolSpecification.builder()
                .name("read_file")
                .description("Read file content")
                .parameters(JsonObjectSchema.builder()
                        .addStringProperty("path", "File path")
                        .build())
                .build();
        toolRegistry.register(toolSpec,readFileToolDefinition);
        return readFileToolDefinition;
    }

    @Bean
    public ToolRegistry toolRegistry(){
        return new ToolRegistry();
    }
}
