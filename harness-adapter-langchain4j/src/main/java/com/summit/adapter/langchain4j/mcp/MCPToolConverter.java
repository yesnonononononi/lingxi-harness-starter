package com.summit.adapter.langchain4j.mcp;

import com.summit.adapter.langchain4j.codec.JsonSchemaSerializer;
import com.summit.core.tool.ToolDefinition;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.mcp.client.McpClient;

/**
 * Turns one MCP tool specification into the core {@link ToolDefinition}.
 *
 * <p>MCP tells the harness nothing about side effects, so the tools are treated as potentially
 * mutating: {@code readOnly(false)} keeps them out of the planning boundary and lets a successful
 * call count as a write. They are not framework-managed either, so a request-level tool whitelist
 * still applies to them.</p>
 */
public class MCPToolConverter {

    /** Remote servers return payloads larger than local tools, hence a more generous default than the common tool properties. */
    private static final Integer DEFAULT_MAX_OUTPUT = 20_000;
    private static final Long DEFAULT_TIMEOUT = 60L;

    private final Integer maxOutput;
    private final Long timeout;

    public MCPToolConverter() {
        this(DEFAULT_MAX_OUTPUT, DEFAULT_TIMEOUT);
    }

    public MCPToolConverter(Integer maxOutput, Long timeout) {
        this.maxOutput = maxOutput;
        this.timeout = timeout;
    }

    public ToolDefinition<MCPToolExecutor> convert(McpClient mcpClient, ToolSpecification toolSpecification) {
        String name = toolSpecification.name();
        return ToolDefinition.<MCPToolExecutor>builder()
                .id(name)
                .name(name)
                .description(toolSpecification.description())
                .parametersJsonSchema(JsonSchemaSerializer.toJson(toolSpecification.parameters()))
                .executor(new MCPToolExecutor(mcpClient, name))
                .maxOutput(maxOutput)
                .timeout(timeout)
                .readOnly(false)
                .planningOnly(false)
                .build();
    }
}
