package com.summit.kernel.tools.search;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.summit.core.tool.ConcurrentPolicy;
import com.summit.core.tool.ToolDefinition;
import com.summit.core.tool.ToolRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Registers the tool-lookup entry.
 *
 * <p>A framework built-in rather than an application tool: the entry is what makes remote tools
 * reachable at all, so leaving its registration to each consumer is how a request ends up with MCP
 * servers connected and no way to call them. It is on unless explicitly switched off, and
 * {@code @ConditionalOnMissingBean} keeps it replaceable — an application that wants a different
 * lookup policy declares its own {@code searchToolDefinition} and this one backs off. That
 * implementation must keep {@link SearchToolExecutor#NAME} as its tool name, since the model's
 * prompt and the disclosure ledger both refer to it by that name.</p>
 *
 * <p>Deliberately no ordering against the registry: the definition is collected through
 * {@code ObjectProvider} at call time, so it does not matter whether the registry is built before or
 * after this bean.</p>
 */
@AutoConfiguration
@EnableConfigurationProperties(SearchToolProperties.class)
public class SearchToolAutoConfiguration {

    @Bean
    @ConditionalOnProperty(
            prefix = "lingxi.agent.runtime.tool.search-tool",
            name = "enabled",
            havingValue = "true",
            matchIfMissing = true
    )
    @ConditionalOnMissingBean(name = "searchToolDefinition")
    public ToolDefinition<SearchToolExecutor> searchToolDefinition(ObjectMapper objectMapper,
                                                                   ObjectProvider<ToolRegistry> toolRegistry,
                                                                   SearchToolProperties properties) {
        String name = SearchToolExecutor.NAME;
        return ToolDefinition.<SearchToolExecutor>builder()
                // Resolved on every call rather than injected: the registry is built from the tool
                // definitions, this one included, so an eager reference would close a cycle.
                .executor(new SearchToolExecutor(objectMapper, toolRegistry::getIfAvailable,
                        properties.getMaxMatches()))
                .id(name)
                .name(name)
                .concurrentPolicy(ConcurrentPolicy.READ_ONLY)
                .description("""
                        Look up the tools callable in this run and return their names, descriptions and
                        parameter schemas. Remote (MCP) tools are listed in the system prompt by name and
                        description only: search for one here to get its schema, and it becomes callable
                        from your next turn on. An empty keyword lists every callable tool.
                        """)
                .parametersJsonSchema("""
                        {
                          "type": "object",
                          "properties": {
                            "keyword": {"type": "string", "description": "Fuzzy keyword matched against tool name and description. Optional; omit to list all callable tools."}
                          }
                        }
                        """)
                .maxOutput(4_000)
                // A sub-second budget would be read as "no timeout" by the tool runtime, so the
                // smallest meaningful value is one second.
                .timeout(Math.max(properties.getTimeout().toSeconds(), 1L))
                .build();
    }
}
