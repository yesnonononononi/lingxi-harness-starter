package com.summit.core.tool;


import lombok.Builder;
import lombok.Data;

import java.util.Objects;

@Builder
@Data
public class ToolExecuteResult {
    private Integer code;
    private String id;
    private ToolDefinition<?> toolSpecification;
    private String toolOutput;
    private ToolResultType toolResultType;
    public static ToolExecuteResult success(String toolOutput){
        return ToolExecuteResult.builder()
                .code(1)
                .toolResultType(ToolResultType.NORMAL)
                .toolOutput(Objects.requireNonNullElse(toolOutput,""))
                .build();
    }
    public static  ToolExecuteResult success(String toolOutput, ToolResultType toolResultType){
        return ToolExecuteResult.builder()
                .code(1)
                .toolResultType(toolResultType)
                .toolOutput(Objects.requireNonNullElse(toolOutput,""))
                .build();
    }

    /**
     * Returns a successful placeholder result that asks the loop to commit this tool round and
     * suspend. No thread or future is retained by the framework.
     */
    public static ToolExecuteResult promise(String toolOutput) {
        return success(toolOutput, ToolResultType.PROMISE);
    }

    public static ToolExecuteResult err(String toolOutput){
        return ToolExecuteResult.builder()
                .code(0)
                .toolResultType(ToolResultType.NORMAL)
                .toolOutput(Objects.requireNonNullElse(toolOutput,""))
                .build();
    }
    public static ToolExecuteResult err(String toolOutput,ToolResultType toolResultType){
        return ToolExecuteResult.builder()
                .code(0)
                .toolResultType(toolResultType)
                .toolOutput(Objects.requireNonNullElse(toolOutput,""))
                .build();
    }

    public  boolean isSuccess(){
        return Integer.valueOf(1).equals(this.code);
    }

    public boolean isPromise() {
        return this.toolResultType == ToolResultType.PROMISE;
    }
}
