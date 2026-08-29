package com.summit.tools.web;


import com.fasterxml.jackson.databind.ObjectMapper;
import com.summit.harnesscore.tool.ToolDefinition;
import com.summit.harnesscore.tool.ToolExecuteResult;
import com.summit.harnesscore.tool.ToolExecution;
import com.summit.harnesscore.tool.ToolExecutor;
import com.summit.tools.arguments.WebSearchArguments;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
@Slf4j
@AllArgsConstructor
public class WebSearchExecutor implements ToolExecutor {
    private final WebSearchEngine webSearchEngine;
    private final ObjectMapper objectMapper;


    @Override
    public @NonNull ToolExecuteResult execute(ToolExecution toolExecution) {
        ToolDefinition<? extends ToolExecutor> toolDefinition = toolExecution.getToolDefinition();
        try {
            String args = toolExecution.getArgs();
            WebSearchArguments arguments = objectMapper.readValue(args, WebSearchArguments.class);
            if(args == null || args.isBlank())return ToolExecuteResult.err(toolExecution.getId(), toolDefinition, "tool execute failed : args is empty");
            log.info("【ToolCall】 web_search :{}",args);
            String res = this.webSearchEngine.search(arguments);
            return ToolExecuteResult.success(toolExecution.getId(), toolDefinition, res);
        }catch (Exception e){
            return ToolExecuteResult.err(toolExecution.getId(), toolDefinition, "tool execute failed : "+e.getMessage());
        }
    }

}
