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
    public static <T extends ToolExecutor>ToolExecuteResult success(String id, ToolDefinition<T> toolSpecification, String toolOutput){
        return ToolExecuteResult.builder()
                .code(1)
                .id(id)
                .toolResultType(ToolResultType.NORMAL)
                .toolSpecification(toolSpecification)
                .toolOutput(Objects.requireNonNullElse(toolOutput,""))
                .build();
    }
    public static <T extends ToolExecutor>ToolExecuteResult success(String id, ToolDefinition<T> toolSpecification, String toolOutput, ToolResultType toolResultType){
        return ToolExecuteResult.builder()
                .code(1)
                .id(id)
                .toolResultType(toolResultType)
                .toolSpecification(toolSpecification)
                .toolOutput(Objects.requireNonNullElse(toolOutput,""))
                .build();
    }

    public static ToolExecuteResult err(String id, ToolDefinition<?> toolSpecification, String toolOutput){
        return ToolExecuteResult.builder()
                .code(0)
                .id(id)
                .toolResultType(ToolResultType.NORMAL)
                .toolSpecification(toolSpecification)
                .toolOutput(Objects.requireNonNullElse(toolOutput,""))
                .build();
    }
    public static ToolExecuteResult err(String id, ToolDefinition<?> toolSpecification, String toolOutput,ToolResultType toolResultType){
        return ToolExecuteResult.builder()
                .code(0)
                .id(id)
                .toolResultType(toolResultType)
                .toolSpecification(toolSpecification)
                .toolOutput(Objects.requireNonNullElse(toolOutput,""))
                .build();
    }

    public  boolean isSuccess(){
        return this.code == 1;
    }
}
