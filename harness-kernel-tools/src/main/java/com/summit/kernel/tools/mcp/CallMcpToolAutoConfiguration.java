package com.summit.kernel.tools.mcp;

import com.summit.core.tool.ConcurrentPolicy;
import com.summit.core.tool.ToolDefinition;
import com.summit.core.tool.ToolRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * Registers the gateway every remote (MCP) tool is called through.
 *
 * <p>Registering it as a framework built-in rather than leaving it to each consumer is what keeps
 * the model-facing tool list constant: the gateway is one more static declaration, so looking a
 * remote tool up no longer adds a function to the next round's request. It is an ordinary static
 * tool for the rest of the runtime: the request whitelist decides whether a run sees it, and a run
 * that does not name it never calls remote tools.</p>
 *
 * <p>Its {@code concurrentPolicy} is serial on purpose: a remote tool's side effects are unknown,
 * and a batched gateway call would otherwise be admitted as read-only. A call that does resolve is
 * rewritten into the remote tool's own call before concurrency is decided, so this policy only
 * covers the error path.</p>
 */
@AutoConfiguration
public class CallMcpToolAutoConfiguration {

    @Bean
    @ConditionalOnProperty(
            prefix = "lingxi.agent.runtime.tool.call-mcp-tool",
            name = "enabled",
            havingValue = "true",
            matchIfMissing = true
    )
    @ConditionalOnMissingBean(name = "callMcpToolDefinition")
    public ToolDefinition<CallMcpToolExecutor> callMcpToolDefinition(ObjectProvider<ToolRegistry> toolRegistry) {
        String name = CallMcpToolExecutor.NAME;
        return ToolDefinition.<CallMcpToolExecutor>builder()
                // Resolved on every call rather than injected: the registry is built from the tool
                // definitions, this one included, so an eager reference would close a cycle.
                .executor(new CallMcpToolExecutor(toolRegistry::getIfAvailable))
                .id(name)
                .name(name)
                .concurrentPolicy(ConcurrentPolicy.SERIAL_MUTATION)
                .description("""
                        Call a remote tool of this run. Remote (MCP) tools are not declared as tools of their own:
                        `search_tool` returns the name and parameter schema of one, and this entry calls it.
                        Pass that exact name as `name` and an object matching its schema as `arguments`, e.g.
                        {"name": "the_name_search_tool_returned", "arguments": {}}. Built-in tools are called
                        directly and must never be passed here.
                        """)
                .parametersJsonSchema("""
                        {
                          "type": "object",
                          "properties": {
                            "name": {"type": "string", "description": "Exact name of the remote tool, as returned by search_tool."},
                            "arguments": {"type": "object", "additionalProperties": true, "description": "Arguments of that remote tool, matching its parameter schema. Omit when the tool takes none."}
                          },
                          "required": ["name"]
                        }
                        """)
                .maxOutput(4_000)
                // Only the error path is timed; a sub-second budget would be read as "no timeout".
                .timeout(1L)
                .build();
    }
}
