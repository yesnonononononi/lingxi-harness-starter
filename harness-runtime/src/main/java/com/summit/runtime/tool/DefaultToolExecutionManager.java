package com.summit.runtime.tool;

import com.summit.harnesscore.compact.Tokenizer;
import com.summit.harnesscore.conversation.event.ToolCallEndEvent;
import com.summit.harnesscore.conversation.event.ToolCallStartEvent;
import com.summit.harnesscore.tool.*;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.jspecify.annotations.NonNull;

import java.util.List;

@AllArgsConstructor
@Getter
public class DefaultToolExecutionManager implements ToolExecutionManager {
    private final ToolExecutionContext toolExecutionContext;
    private final CommonToolConfig commonToolConfig;
    private final Tokenizer tokenizer;


    @Override
    public List<ToolExecuteResult> execute(ToolExecuteCommand toolExecuteCommand) {
        return toolExecuteCommand.requests().stream()
                .map(request -> {
                    try {
                        this.toolExecutionContext.runtimeEventPublisher().onToolCall(new ToolCallStartEvent(toolExecuteCommand.executionId(), request.name(), request.arguments()));

                        ToolDefinition<?> toolDef = this.toolExecutionContext.toolRegistry().getTool(request.name());

                        if (toolDef == null) {
                            return ToolExecuteResult.err(request.id(), null, "Tool not found");
                        }
                        ToolExecuteResult result = ToolResultLimiter.limit(toolDef.executor().execute(createToolExecution(request, toolDef)),toolDef,this.tokenizer);

                        this.toolExecutionContext.runtimeEventPublisher().onToolCallOutput(new ToolCallEndEvent(toolExecuteCommand.executionId(), formatEventToolOutput(result.getToolOutput())));

                        return result;

                    } catch (Exception e) {
                        // keep the tool name in the error result so the model can tell which tool failed
                        ToolDefinition<?> toolDef = this.toolExecutionContext.toolRegistry().getTool(request.name());
                        ToolSpecification spec = toolDef == null ? null : toolDef.toolSpecification();
                        this.toolExecutionContext.runtimeEventPublisher().onToolCallOutput(new ToolCallEndEvent(toolExecuteCommand.executionId(), "Tool execution error" + e.getMessage()));
                        return ToolExecuteResult.err(request.id(), spec, "Tool execution error" + e.getMessage());
                    }
                })

                .toList();
    }

    @Override
    public ToolRegistry toolRegistry() {
        return this.toolExecutionContext.toolRegistry();
    }

    private ToolExecution createToolExecution(@NonNull ToolExecutionRequest request, ToolDefinition<?> tool) {
        return ToolExecution.builder()
                .id(request.id())
                .toolDefinition(tool)
                .workspace(this.toolExecutionContext.workspace())
                .args(request.arguments())
                .build();
    }

    /**
     * Format the tool output to be displayed in the event.
     *
     * @param output The tool output to be formatted.
     * @return The formatted tool output.
     */
    private String formatEventToolOutput(String output) {
        Integer maxChar = commonToolConfig.maxToolOutputDisplay();
        return output.length() > maxChar ? output.substring(0, maxChar) + "..." : output;
    }

}
