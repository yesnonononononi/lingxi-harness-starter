package com.summit.runtime.tool;

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


    @Override
    public List<ToolExecuteResult> execute(ToolExecuteCommand toolExecuteCommand) {
        return toolExecuteCommand.requests().stream()
                .map(request -> {
                    Tool tool;
                    try {
                        this.toolExecutionContext.runtimeEventPublisher().onToolCall(new ToolCallStartEvent(toolExecuteCommand.executionId(), request.name()));

                        ToolSpecification tooSpec = this.toolExecutionContext.toolRegistry().getToolSpec(request.name());

                        if ((tool = this.toolExecutionContext.toolRegistry().getTool(request.name())) == null) {
                            return ToolExecuteResult.err(request.id(), tooSpec, "Tool not found");
                        }

                        ToolExecuteResult result = tool.executor().execute(
                                createToolExecution(request, tooSpec)
                        );

                        this.toolExecutionContext.runtimeEventPublisher().onToolCallOutput(new ToolCallEndEvent(toolExecuteCommand.executionId(),formatEventToolOutput( result.getToolOutput())));

                        return result;

                    }catch (Exception e){
                        this.toolExecutionContext.runtimeEventPublisher().onToolCallOutput(new ToolCallEndEvent(toolExecuteCommand.executionId(), "Tool execution error" + e.getMessage()));
                        return ToolExecuteResult.err(request.id(), null, "Tool execution error" + e.getMessage());
                    }
                })

                .toList();
    }

    @Override
    public ToolRegistry toolRegistry() {
        return this.toolExecutionContext.toolRegistry();
    }

    private ToolExecution createToolExecution(@NonNull ToolExecutionRequest request, ToolSpecification tool) {
        return ToolExecution.builder()
                .id(request.id())
                .toolSpecification(tool)
                .workspace(this.toolExecutionContext.workspace())
                .args(request.arguments())
                .build();
    }

    /**
     * Format the tool output to be displayed in the event.
     * @param output The tool output to be formatted.
     * @return The formatted tool output.
     */
    private String formatEventToolOutput(String output){
        Integer maxChar = commonToolConfig.maxToolOutputDisplay();
        return output.length() > maxChar ? output.substring(0, maxChar)+"..." : output;
    }

}
