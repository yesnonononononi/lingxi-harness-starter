package com.summit.tools.web;


import com.summit.harnesscore.tool.ToolExecuteResult;
import com.summit.harnesscore.tool.ToolExecution;
import com.summit.harnesscore.tool.ToolExecutor;
import lombok.AllArgsConstructor;
import org.jspecify.annotations.NonNull;

@AllArgsConstructor
public class WebSearchExecutor implements ToolExecutor {
    private final WebSearchEngine webSearchEngine;



    @Override
    public @NonNull ToolExecuteResult execute(ToolExecution toolExecution) {
        try {
            String args = toolExecution.getArgs();
            if(args == null || args.isBlank())return ToolExecuteResult.err(toolExecution.getId(), toolExecution.getToolSpecification(), "tool execute failed : args is empty");
            String res = this.webSearchEngine.search(args);
            return ToolExecuteResult.success(toolExecution.getId(), toolExecution.getToolSpecification(), res);
        }catch (Exception e){
            return ToolExecuteResult.err(toolExecution.getId(), toolExecution.getToolSpecification(), "tool execute failed : "+e.getMessage());
        }
    }

}
