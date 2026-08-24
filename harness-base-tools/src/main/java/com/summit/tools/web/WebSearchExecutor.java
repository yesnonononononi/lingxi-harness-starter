package com.summit.tools.web;


import com.summit.harnesscore.tool.ToolExecuteResult;
import com.summit.harnesscore.tool.ToolExecution;
import com.summit.harnesscore.tool.ToolExecutor;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
@Slf4j
@AllArgsConstructor
public class WebSearchExecutor implements ToolExecutor {
    private final WebSearchEngine webSearchEngine;



    @Override
    public @NonNull ToolExecuteResult execute(ToolExecution toolExecution) {
        try {
            String args = toolExecution.getArgs();
            if(args == null || args.isBlank())return ToolExecuteResult.err(toolExecution.getId(), toolExecution.getToolSpecification(), "tool execute failed : args is empty");
            log.info("【ToolCall】 web_search :{}",args);
            String res = this.webSearchEngine.search(args);
            return ToolExecuteResult.success(toolExecution.getId(), toolExecution.getToolSpecification(), res);
        }catch (Exception e){
            return ToolExecuteResult.err(toolExecution.getId(), toolExecution.getToolSpecification(), "tool execute failed : "+e.getMessage());
        }
    }

}
