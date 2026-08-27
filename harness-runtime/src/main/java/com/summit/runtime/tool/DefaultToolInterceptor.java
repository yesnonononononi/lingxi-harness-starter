package com.summit.runtime.tool;


import com.summit.harnesscore.compact.Tokenizer;
import com.summit.harnesscore.interceptor.InvocationContext;
import com.summit.harnesscore.tool.ToolExecuteResult;
import com.summit.harnesscore.tool.ToolExecution;
import com.summit.harnesscore.tool.ToolInterceptor;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class DefaultToolInterceptor implements ToolInterceptor {
    private final Tokenizer tokenizer;

    @Override
    public void pre(InvocationContext<ToolExecution> invocationContext) {

    }

    @Override
    public void after(InvocationContext<ToolExecution> invocationContext, Object result) {

        ToolExecution toolExecution = invocationContext.getContext();

        if (result instanceof ToolExecuteResult toolExecuteResult) {

            String toolOutput = toolExecuteResult.getToolOutput();


            Integer maxOutput = toolExecution.getToolDefinition().maxOutput();

            String truncatedOutput = this.tokenizer.truncate(toolOutput, maxOutput);

            toolExecuteResult.setToolOutput(truncatedOutput);

        }
    }

    @Override
    public Integer order() {
        return Integer.MIN_VALUE; // the highest priority
    }


}
