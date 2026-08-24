package com.summit.harness.springbootautoconfigure.conf;

import com.fasterxml.jackson.databind.ObjectMapper;

import com.summit.harness.springbootautoconfigure.properties.CommonToolProperties;
import com.summit.harness.springbootautoconfigure.properties.TerminalToolProperties;
import com.summit.harness.springbootautoconfigure.properties.WebSearchToolProperties;
import com.summit.harnesscore.conversation.event.RuntimeEventPublisher;
import com.summit.harnesscore.runtime.Workspace;
import com.summit.harnesscore.tool.Tool;
import com.summit.harnesscore.tool.ToolExecutionContext;
import com.summit.harnesscore.tool.ToolExecutionManager;
import com.summit.harnesscore.tool.ToolRegistry;
import com.summit.runtime.tool.DefaultToolExecutionManager;
import com.summit.runtime.tool.CommonToolConfig;
import com.summit.tools.compact.ContextCompactToolDefinition;
import com.summit.tools.terminal.ExecuteCommandToolDefinition;
import com.summit.tools.terminal.TerminalConfig;
import com.summit.tools.web.WebSearchConfig;
import com.summit.tools.web.WebSearchEngine;
import com.summit.tools.web.WebSearchToolDefinition;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.net.http.HttpClient;

@Slf4j
@EnableConfigurationProperties({WebSearchToolProperties.class, TerminalToolProperties.class, CommonToolProperties.class})
@AutoConfiguration
public class CommonToolAutoConfiguration {


    @Bean
    @ConditionalOnProperty(
            prefix = "lingxi.agent.runtime.tool.terminal",
            name = "enabled",
            havingValue = "true"
    )
    public Tool executeCommandToolDefinition(ObjectMapper objectMapper, ToolRegistry toolRegistry, TerminalToolProperties terminalToolProperties) {
        ExecuteCommandToolDefinition executeCommandToolDefinition = new ExecuteCommandToolDefinition(objectMapper,
                TerminalConfig.builder()
                        .timeout(terminalToolProperties.getTimeout())
                        .build()
        );
        ToolSpecification toolSpec = ToolSpecification.builder()
                .name(executeCommandToolDefinition.name())
                .description(" execute command ")
                .parameters(JsonObjectSchema.builder()
                        .addStringProperty("instruction", "require notice system os and use PowerShell if windows. Instruction,example : ll  it is a required parameter")
                        .build())
                .build();
        toolRegistry.register(toolSpec, executeCommandToolDefinition);
        return executeCommandToolDefinition;
    }

    @Bean
    public Tool contextCompactToolDefinition(ToolRegistry toolRegistry, @Qualifier("defaultContextCompactModel") ChatModel defaultContextCompactModel) {
        ContextCompactToolDefinition contextCompactToolDefinition = new ContextCompactToolDefinition(defaultContextCompactModel);
        ToolSpecification toolSpecification = ToolSpecification.builder()
                .name(contextCompactToolDefinition.name())
                .description("""
                        Context compression tool. Call this tool when the conversation has accumulated too much content and you need to summarize the history to free up context.
                        The 'context' parameter is required and must contain the full conversation history (as a JSON string of messages) that needs to be compressed.
                      
                        """)
                .parameters(
                        JsonObjectSchema.builder()
                                .addStringProperty("context", "The full conversation history to compress, as a JSON string. Required parameter.")
                                .build()
                )
                .build();
        toolRegistry.register(toolSpecification, contextCompactToolDefinition);
        return contextCompactToolDefinition;
    }


    @Bean
    @ConditionalOnProperty(
            prefix = "lingxi.agent.runtime.tool.web_search",
            name = "enabled",
            havingValue = "true"
    )
    public Tool webSearchToolDefinition(ToolRegistry toolRegistry, WebSearchEngine webSearchEngine) {
        WebSearchToolDefinition webSearchToolDefinition = new WebSearchToolDefinition(webSearchEngine);
        ToolSpecification toolSpec = ToolSpecification.builder()
                .name(webSearchToolDefinition.name())
                .description(" browser search ")
                .parameters(JsonObjectSchema.builder()
                        .addStringProperty("query", "The search query to execute. it is a required parameter")
                        .addNumberProperty("max_results", "Maximum number of results to return. it is an optional parameter")
                        .addStringProperty("start_date", " Will return all results after the specified start date based on publish date or last updated date. Required to be written in the format YYYY-MM-DD. it is an optional parameter")
                        .addStringProperty("end_date", "  Will return all results before the specified end date based on publish date or last updated date. Required to be written in the format YYYY-MM-DD. it is an optional parameter")
                        .build())
                .build();
        toolRegistry.register(toolSpec, webSearchToolDefinition);
        log.info("webSearchToolDefinition successfully registered");
        return webSearchToolDefinition;
    }

    @Bean
    @ConditionalOnProperty(
            prefix = "lingxi.agent.runtime.tool.web_search",
            name = "enabled",
            havingValue = "true"
    )
    public WebSearchEngine webSearchEngine(WebSearchToolProperties webSearchToolProperties) {
        return new WebSearchEngine(WebSearchConfig.builder()
                .baseUrl(webSearchToolProperties.getBaseUrl())
                .apiKey(webSearchToolProperties.getApiKey())
                .timeout(webSearchToolProperties.getTimeout())
                .build()
                , HttpClient.newHttpClient());
    }


    @Bean
    public ToolRegistry toolRegistry() {
        return new ToolRegistry();
    }


    @Bean
    public ToolExecutionManager defaultToolExecutionManager(ToolRegistry toolRegistry, Workspace workspace, RuntimeEventPublisher runtimeEventPublisher, CommonToolConfig commonToolConfig) {
        return new DefaultToolExecutionManager(
                ToolExecutionContext.builder()
                        .toolRegistry(toolRegistry)
                        .workspace(workspace)
                        .runtimeEventPublisher(runtimeEventPublisher)
                        .build(),
                commonToolConfig
        );
    }

    @Bean
    public CommonToolConfig commonToolConfig(CommonToolProperties commonToolProperties) {
        return CommonToolConfig.builder()
                .maxToolOutputDisplay(commonToolProperties.getMaxToolOutputDisplay())
                .build();
    }
}
