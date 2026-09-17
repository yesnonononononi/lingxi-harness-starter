package com.summit.adapter.langchain4j.mcp;

import com.summit.core.mcp.McpToolExecutor;
import com.summit.core.tool.ToolDefinition;
import com.summit.core.tool.ToolExecuteResult;
import com.summit.core.tool.ToolExecution;
import com.summit.core.tool.ToolExecutor;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.service.tool.ToolExecutionResult;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Forwards one harness tool call to a tool of an MCP server.
 *
 * <p>One instance is bound to one remote tool. Timeout, output truncation and the conversion of
 * thrown exceptions into a failed result are handled by the runtime, so this class only builds the
 * MCP request, calls the server and maps the answer back.</p>
 */
@Slf4j
@RequiredArgsConstructor
public class MCPToolExecutor implements McpToolExecutor {

    private static final String FAILURE_MESSAGE = "mcp tool execute failed : ";
    private static final String EMPTY_ARGUMENTS = "{}";

    private final McpClient mcpClient;

    /** Tool name as declared by the MCP server; falls back to the model-facing name when absent. */
    private final String remoteToolName;

    @Override
    public @NonNull ToolExecuteResult execute(ToolExecution toolExecution) {
        String id = toolExecution.getId();
        ToolDefinition<? extends ToolExecutor> toolDefinition = toolExecution.getToolDefinition();
        String toolName = resolveToolName(toolDefinition);

        if (toolName == null || toolName.isBlank()) {
            return ToolExecuteResult.err(FAILURE_MESSAGE + "tool name is missing");
        }

        try {
            ToolExecutionRequest request = ToolExecutionRequest.builder()
                    .id(id)
                    .name(toolName)
                    .arguments(arguments(toolExecution.getArgs()))
                    .build();

            ToolExecutionResult result = mcpClient.executeTool(request);

            if (result == null) {
                return ToolExecuteResult.err(
                        FAILURE_MESSAGE + "empty result returned by MCP server");
            }
            if (result.isError()) {
                log.warn("【ToolCall】 mcp {} returned an error :{}", toolName, result.resultText());
                return ToolExecuteResult.err(result.resultText());
            }
            return ToolExecuteResult.success(result.resultText());
        } catch (Exception e) {
            log.warn("【ToolCall】 mcp {} failed", toolName, e);
            return ToolExecuteResult.err(FAILURE_MESSAGE + e.getMessage());
        }
    }

    private String resolveToolName(ToolDefinition<? extends ToolExecutor> toolDefinition) {
        if (remoteToolName != null && !remoteToolName.isBlank()) {
            return remoteToolName;
        }
        return toolDefinition == null ? null : toolDefinition.name();
    }

    /** MCP expects the arguments as a JSON object, so a tool without parameters still gets {@code {}}. */
    private static String arguments(String args) {
        return args == null || args.isBlank() ? EMPTY_ARGUMENTS : args;
    }
}
