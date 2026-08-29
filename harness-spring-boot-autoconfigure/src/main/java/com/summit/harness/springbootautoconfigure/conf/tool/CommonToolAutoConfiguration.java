package com.summit.harness.springbootautoconfigure.conf.tool;

import com.fasterxml.jackson.databind.ObjectMapper;

import com.summit.harness.springbootautoconfigure.properties.tool.CommonToolProperties;
import com.summit.harness.springbootautoconfigure.properties.tool.ContextCompactToolProperties;
import com.summit.harness.springbootautoconfigure.properties.tool.TerminalToolProperties;
import com.summit.harness.springbootautoconfigure.properties.tool.WebSearchToolProperties;
import com.summit.harnesscore.conversation.event.RuntimeEventPublisher;
import com.summit.harnesscore.interceptor.InterceptorProcessor;
import com.summit.harnesscore.model.ChatModel;
import com.summit.harnesscore.runtime.Workspace;
import com.summit.harnesscore.tool.*;
import com.summit.runtime.tool.DefaultToolExecutionManager;
import com.summit.runtime.configs.CommonToolConfig;
import com.summit.tools.compact.ContextCompactToolExecutor;
import com.summit.tools.terminal.CommandToolDefinitionExecutor;
import com.summit.tools.web.WebSearchConfig;
import com.summit.tools.web.WebSearchEngine;
import com.summit.tools.web.WebSearchExecutor;
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
        String name = "execute_command";
        ToolDefinition<CommandToolDefinitionExecutor> definition = ToolDefinition.<CommandToolDefinitionExecutor>builder()
                .executor(new CommandToolDefinitionExecutor(objectMapper))
                .id(name)
                .name(name)
                .description("""
                        Execute a terminal command. Only use it when you really need to run a command (build/run/install/git/start server/network check).
                        don't use prefix to the command like : Get-ChildItem -Name
                        WARNING: output is truncated, keep the command precise and avoid large outputs.
                        DO NOT use this tool to replace dedicated tools:
                        - read files -> use read_file
                        - modify files -> use edit_file
                        - search web / external info -> use web_search
                        - inspect or list project files -> use dedicated file tools, avoid recursive listings like dir /s /b or Get-ChildItem -Recurse
                        """)
                .parametersJsonSchema("""
                        {
                          "type": "object",
                          "properties": {
                            "command": {"type": "string", "description": "require notice system os and use PowerShell if windows. Instruction,example : ll  it is a required parameter"}
                          }
                        }
                        """)
                .maxOutput(Objects.requireNonNullElseGet(terminalToolProperties.getMaxOutput(), commonToolProperties::getMaxOutput))
                .timeout(Objects.requireNonNullElseGet(terminalToolProperties.getTimeout(), commonToolProperties::getTimeout))
                .build();
        toolRegistry.register(name, definition);
        return definition;
    }

    @Bean
    @ConditionalOnProperty(
            prefix = "lingxi.agent.runtime.tool.context-compact",
            name = "enabled",
            havingValue = "true"
    )
    public ToolDefinition<ContextCompactToolExecutor> contextCompactToolDefinition(ToolRegistry toolRegistry, @Qualifier("defaultContextCompactModel") ChatModel defaultContextCompactModel, ContextCompactToolProperties contextCompactToolProperties, CommonToolProperties commonToolProperties) {
        String name = "compact_context";
        ToolDefinition<ContextCompactToolExecutor> definition = ToolDefinition.<ContextCompactToolExecutor>builder()
                .executor(new ContextCompactToolExecutor(defaultContextCompactModel))
                .id(name)
                .name(name)
                .description("""
                        Context compression tool. Call this tool when the conversation has accumulated too much content and you need to summarize the history to free up context.
                        The 'context' parameter is required and must contain the full conversation history (as a JSON string of messages) that needs to be compressed.

                        """)
                .parametersJsonSchema("""
                        {
                          "type": "object",
                          "properties": {
                            "context": {"type": "string", "description": "The full conversation history to compress, as a JSON string. Required parameter."}
                          }
                        }
                        """)
                .maxOutput(Objects.requireNonNullElseGet(contextCompactToolProperties.getMaxOutput(),commonToolProperties::getMaxOutput))
                .timeout(Objects.requireNonNullElseGet(contextCompactToolProperties.getTimeout(),commonToolProperties::getTimeout))
                .build();
        toolRegistry.register(name, definition);
        return definition;
    }


    @Bean
    @ConditionalOnProperty(
            prefix = "lingxi.agent.runtime.tool.web-search",
            name = "enabled",
            havingValue = "true"
    )
    public ToolDefinition<WebSearchExecutor> webSearchToolDefinition(ToolRegistry toolRegistry, WebSearchEngine webSearchEngine, WebSearchToolProperties webSearchToolProperties, CommonToolProperties commonToolProperties, ObjectMapper objectMapper) {
        String name = "web_search";
        ToolDefinition<WebSearchExecutor> definition = ToolDefinition.<WebSearchExecutor>builder()
                .executor(new WebSearchExecutor(webSearchEngine,objectMapper))
                .id(name)
                .name(name)
                .description("Search the web for external/current information. PREFERRED for any online lookup; do NOT use terminal commands instead.")
                .parametersJsonSchema(("""
                        {
                          "type": "object",
                          "properties": {
                            "query": {"type": "string", "description": "The search query to execute. it is a required parameter"},
                            "max_results": {"type": "number", "description": "Maximum number of results to return. it is an optional parameter max :%s"},
                            "start_date": {"type": "string", "description": " Will return all results after the specified start date based on publish date or last updated date. Required to be written in the format YYYY-MM-DD. it is an optional parameter"},
                            "end_date": {"type": "string", "description": "  Will return all results before the specified end date based on publish date or last updated date. Required to be written in the format YYYY-MM-DD. it is an optional parameter"}
                          }
                        }
                        """).formatted(String.valueOf(webSearchToolProperties.getMaxResult())))
                .maxOutput(Objects.requireNonNullElseGet(webSearchToolProperties.getMaxOutput(), commonToolProperties::getMaxOutput))
                .timeout(Objects.requireNonNullElseGet(webSearchToolProperties.getTimeout(), commonToolProperties::getTimeout))
                .build();
        toolRegistry.register(name, definition);
        log.info("webSearchToolDefinition successfully registered");
        return definition;
    }

    @Bean
    @ConditionalOnProperty(
            prefix = "lingxi.agent.runtime.tool.web-search",
            name = "enabled",
            havingValue = "true"
    )
    public WebSearchEngine webSearchEngine(WebSearchToolProperties webSearchToolProperties, CommonToolProperties commonToolProperties) {
        return new WebSearchEngine(WebSearchConfig.builder()
                .baseUrl(webSearchToolProperties.getBaseUrl())
                .apiKey(webSearchToolProperties.getApiKey())
                .maxResult(webSearchToolProperties.getMaxResult())
                .timeout(Objects.requireNonNullElseGet(webSearchToolProperties.getTimeout(), commonToolProperties::getTimeout))
                .build()
                , HttpClient.newHttpClient());
    }


    @Bean
    public ToolRegistry toolRegistry() {
        return new ToolRegistry();
    }


    @Bean
    public ToolExecutionManager defaultToolExecutionManager(ToolRegistry toolRegistry, Workspace workspace, RuntimeEventPublisher runtimeEventPublisher, CommonToolConfig commonToolConfig,  InterceptorProcessor<ToolInterceptor,ToolExecution> interceptorProcessor) {
        return new DefaultToolExecutionManager(
                ToolExecutionContext.builder()
                        .toolRegistry(toolRegistry)
                        .workspace(workspace)
                        .runtimeEventPublisher(runtimeEventPublisher)
                        .build(),
                interceptorProcessor,
                commonToolConfig
        );
    }

    @Bean
    public CommonToolConfig commonToolConfig(CommonToolProperties commonToolProperties) {
        return CommonToolConfig.builder()
                .maxToolOutputDisplay(commonToolProperties.getMaxOutput())
                .build();
    }
}
