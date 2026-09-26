package com.summit.adapter.langchain4j.mcp;

import com.summit.adapter.langchain4j.codec.JsonSchemaSerializer;
import com.summit.core.tool.ConcurrentPolicy;
import com.summit.core.tool.ToolDefinition;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.mcp.client.McpClient;

/** Turns one MCP tool specification into the core {@link ToolDefinition}. */
public class MCPToolConverter {

    /** Remote servers return payloads larger than local tools, hence a more generous default than the common tool properties. */
    private static final Integer DEFAULT_MAX_OUTPUT = 20_000;
    private static final Long DEFAULT_TIMEOUT = 60L;

    private final Integer maxOutput;
    private final Long timeout;
    private final String namePrefix;

    public MCPToolConverter() {
        this(DEFAULT_MAX_OUTPUT, DEFAULT_TIMEOUT);
    }

    public MCPToolConverter(Integer maxOutput, Long timeout) {
        this(maxOutput, timeout, "");
    }

    public MCPToolConverter(Integer maxOutput, Long timeout, String namePrefix) {
        this.maxOutput = maxOutput;
        this.timeout = timeout;
        this.namePrefix = namePrefix == null ? "" : namePrefix;
    }

    public ToolDefinition<MCPToolExecutor> convert(McpClient mcpClient, ToolSpecification toolSpecification) {
        String name = namePrefix + toolSpecification.name();
        return ToolDefinition.<MCPToolExecutor>builder()
                .id(name)
                .name(name)
                .description(toolSpecification.description())
                .parametersJsonSchema(JsonSchemaSerializer.toJson(toolSpecification.parameters()))
                .executor(new MCPToolExecutor(mcpClient, toolSpecification.name()))
                .maxOutput(maxOutput)
                .timeout(timeout)
                .concurrentPolicy(ConcurrentPolicy.SERIAL_MUTATION)
                .build();
    }
}
