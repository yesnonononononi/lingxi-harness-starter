package com.summit.kernel.tools.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.summit.core.tool.ConcurrentPolicy;
import com.summit.core.tool.ToolDefinition;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Registers the {@code list_mcp_tools} entry.
 *
 * <p>{@code @ConditionalOnMissingBean} keeps it replaceable; a replacement must keep
 * {@link ListMcpToolsExecutor#NAME}, since the assembled prompt refers to the tool by that name.
 */
@AutoConfiguration
@EnableConfigurationProperties(ListMcpToolsProperties.class)
public class ListMcpToolsAutoConfiguration {

    @Bean
    @ConditionalOnProperty(
            prefix = "lingxi.agent.runtime.tool.list-mcp-tools",
            name = "enabled",
            havingValue = "true",
            matchIfMissing = true
    )
    @ConditionalOnMissingBean(name = "listMcpToolsDefinition")
    public ToolDefinition<ListMcpToolsExecutor> listMcpToolsDefinition(ObjectMapper objectMapper,
                                                                       ListMcpToolsProperties properties) {
        String name = ListMcpToolsExecutor.NAME;
        return ToolDefinition.<ListMcpToolsExecutor>builder()
                .executor(new ListMcpToolsExecutor(objectMapper, properties.getMaxTools()))
                .id(name)
                .name(name)
                .concurrentPolicy(ConcurrentPolicy.READ_ONLY)
                .description("""
                        List the tools a connected MCP server contributed to this run, by name and description.
                        The system prompt shows one line per MCP server with how many tools it has; call this with
                        that server's name to see which tools they are. Returns no parameter schema, so it does not
                        make anything callable — use search_tool once you have picked a tool name. Omitting the
                        name lists the tools of every connected server.
                        """)
                .parametersJsonSchema("""
                        {
                          "type": "object",
                          "properties": {
                            "mcpName": {"type": "string", "description": "Name of the MCP server to list, as shown in the system prompt's MCP section. Optional; omit to list every connected server."},
                            "limit": {"type": "integer", "description": "Maximum number of tools to return. Optional."}
                          }
                        }
                        """)
                .maxOutput(4_000)
                // A sub-second budget would be read as "no timeout", so one second is the floor.
                .timeout(Math.max(properties.getTimeout().toSeconds(), 1L))
                .build();
    }
}
