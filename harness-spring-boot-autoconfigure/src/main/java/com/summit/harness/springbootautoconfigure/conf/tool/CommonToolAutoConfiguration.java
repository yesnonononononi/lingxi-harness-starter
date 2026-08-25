package com.summit.harness.springbootautoconfigure.conf.tool;

import com.fasterxml.jackson.databind.ObjectMapper;

import com.summit.harness.springbootautoconfigure.properties.tool.CommonToolProperties;
import com.summit.harness.springbootautoconfigure.properties.tool.ContextCompactToolProperties;
import com.summit.harness.springbootautoconfigure.properties.tool.TerminalToolProperties;
import com.summit.harness.springbootautoconfigure.properties.tool.WebSearchToolProperties;
import com.summit.harnesscore.compact.Tokenizer;
import com.summit.harnesscore.conversation.event.RuntimeEventPublisher;
import com.summit.harnesscore.runtime.Workspace;
import com.summit.harnesscore.tool.ToolDefinition;
import com.summit.harnesscore.tool.ToolExecutionContext;
import com.summit.harnesscore.tool.ToolExecutionManager;
import com.summit.harnesscore.tool.ToolRegistry;
import com.summit.runtime.tool.DefaultToolExecutionManager;
import com.summit.runtime.tool.CommonToolConfig;
import com.summit.tools.compact.ContextCompactToolExecutor;
import com.summit.tools.terminal.CommandToolDefinitionExecutor;
import com.summit.tools.web.WebSearchConfig;
import com.summit.tools.web.WebSearchEngine;
import com.summit.tools.web.WebSearchExecutor;
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
import java.util.Objects;

@Slf4j
@EnableConfigurationProperties({WebSearchToolProperties.class, TerminalToolProperties.class, CommonToolProperties.class, ContextCompactToolProperties.class})
@AutoConfiguration
public class CommonToolAutoConfiguration {


    @Bean
    @ConditionalOnProperty(
            prefix = "lingxi.agent.runtime.tool.terminal",
            name = "enabled",
            havingValue = "true"
    )
    public ToolDefinition<CommandToolDefinitionExecutor> executeCommandToolDefinition(ObjectMapper objectMapper, ToolRegistry toolRegistry, TerminalToolProperties terminalToolProperties, CommonToolProperties commonToolProperties) {
        ToolSpecification toolSpec = ToolSpecification.builder()
                .name("execute_command")
                .description("""
                        Execute a terminal command. Only use it when you really need to run a command (build/run/install/git/start server/network check).
                        WARNING: output is truncated to %d chars, keep the command precise and avoid large outputs.
                        DO NOT use this tool to replace dedicated tools:
                        - read files -> use read_file
                        - modify files -> use edit_file
                        - search web / external info -> use web_search
                        - inspect or list project files -> use dedicated file tools, avoid recursive listings like dir /s /b or Get-ChildItem -Recurse
                        """)
                .parameters(JsonObjectSchema.builder()
                        .addStringProperty("instruction", "require notice system os and use PowerShell if windows. Instruction,example : ll  it is a required parameter")
                        .build())
                .build();
        ToolDefinition<CommandToolDefinitionExecutor> definition = ToolDefinition.<CommandToolDefinitionExecutor>builder()
                .executor(new CommandToolDefinitionExecutor(objectMapper))
                .maxOutput(Objects.requireNonNullElseGet(terminalToolProperties.getMaxOutput(), commonToolProperties::getMaxOutput))
                .timeout(Objects.requireNonNullElseGet(terminalToolProperties.getTimeout(), commonToolProperties::getTimeout))
                .toolSpecification(toolSpec)
                .build();
        toolRegistry.register(toolSpec.name(), definition);
        return definition;
    }

    @Bean
    @ConditionalOnProperty(
            prefix = "lingxi.agent.runtime.tool.context-compact",
            name = "enabled",
            havingValue = "true"
    )
    public ToolDefinition<ContextCompactToolExecutor> contextCompactToolDefinition(ToolRegistry toolRegistry, @Qualifier("defaultContextCompactModel") ChatModel defaultContextCompactModel, ContextCompactToolProperties contextCompactToolProperties, CommonToolProperties commonToolProperties) {
        ToolSpecification toolSpecification = ToolSpecification.builder()
                .name("context_compact")
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
        ToolDefinition<ContextCompactToolExecutor> definition = ToolDefinition.<ContextCompactToolExecutor>builder()
                .executor(new ContextCompactToolExecutor(defaultContextCompactModel))
                .toolSpecification(toolSpecification)
                .maxOutput(Objects.requireNonNullElseGet(contextCompactToolProperties.getMaxOutput(),commonToolProperties::getMaxOutput))
                .timeout(Objects.requireNonNullElseGet(contextCompactToolProperties.getTimeout(),commonToolProperties::getTimeout))
                .build();
        toolRegistry.register(toolSpecification.name(), definition);
        return definition;
    }


    @Bean
    @ConditionalOnProperty(
            prefix = "lingxi.agent.runtime.tool.web-search",
            name = "enabled",
            havingValue = "true"
    )
    public ToolDefinition<WebSearchExecutor> webSearchToolDefinition(ToolRegistry toolRegistry, WebSearchEngine webSearchEngine, WebSearchToolProperties webSearchToolProperties, CommonToolProperties commonToolProperties, ObjectMapper objectMapper) {

        ToolSpecification toolSpec = ToolSpecification.builder()
                .name("web_search")
                .description("Search the web for external/current information. PREFERRED for any online lookup; do NOT use terminal commands instead.")
                .parameters(JsonObjectSchema.builder()
                        .addStringProperty("query", "The search query to execute. it is a required parameter")
                        .addNumberProperty("max_results", "Maximum number of results to return. it is an optional parameter max :"+ webSearchToolProperties.getMaxResult())
                        .addStringProperty("start_date", " Will return all results after the specified start date based on publish date or last updated date. Required to be written in the format YYYY-MM-DD. it is an optional parameter")
                        .addStringProperty("end_date", "  Will return all results before the specified end date based on publish date or last updated date. Required to be written in the format YYYY-MM-DD. it is an optional parameter")
                        .build())
                .build();
        ToolDefinition<WebSearchExecutor> definition = ToolDefinition.<WebSearchExecutor>builder()
                .executor(new WebSearchExecutor(webSearchEngine,objectMapper))
                .toolSpecification(toolSpec)
                .maxOutput(Objects.requireNonNullElseGet(webSearchToolProperties.getMaxOutput(), commonToolProperties::getMaxOutput))
                .timeout(Objects.requireNonNullElseGet(webSearchToolProperties.getTimeout(), commonToolProperties::getTimeout))
                .build();
        toolRegistry.register(toolSpec.name(), definition);
        log.info("webSearchToolDefinition successfully registered");
        return definition;
    }

    @Bean
    @ConditionalOnProperty(
            prefix = "lingxi.agent.runtime.tool.web-search",
            name = "enabled",
            havingValue = "true"
    )
    public WebSearchEngine webSearchEngine(WebSearchToolProperties webSearchToolProperties) {
        return new WebSearchEngine(WebSearchConfig.builder()
                .baseUrl(webSearchToolProperties.getBaseUrl())
                .apiKey(webSearchToolProperties.getApiKey())
                .maxResult(webSearchToolProperties.getMaxResult())
                .timeout(webSearchToolProperties.getTimeout ())
                .build()
                , HttpClient.newHttpClient());
    }


    @Bean
    public ToolRegistry toolRegistry() {
        return new ToolRegistry();
    }


    @Bean
    public ToolExecutionManager defaultToolExecutionManager(ToolRegistry toolRegistry, Workspace workspace, RuntimeEventPublisher runtimeEventPublisher, CommonToolConfig commonToolConfig, Tokenizer tokenizer) {
        return new DefaultToolExecutionManager(
                ToolExecutionContext.builder()
                        .toolRegistry(toolRegistry)
                        .workspace(workspace)
                        .runtimeEventPublisher(runtimeEventPublisher)
                        .build(),
                commonToolConfig,
                tokenizer
        );
    }

    @Bean
    public CommonToolConfig commonToolConfig(CommonToolProperties commonToolProperties) {
        return CommonToolConfig.builder()
                .maxToolOutputDisplay(commonToolProperties.getMaxOutput())
                .build();
    }
}
