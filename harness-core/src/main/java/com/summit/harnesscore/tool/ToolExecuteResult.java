package com.summit.harnesscore.tool;

import dev.langchain4j.agent.tool.ToolSpecification;
import lombok.Builder;
import lombok.Data;

import java.util.Objects;

@Builder
@Data
public class ToolExecuteResult {
    private Integer code;
    private String id;
    private ToolSpecification toolSpecification;
    private String toolOutput;
    private ToolResultType toolResultType;
    public static ToolExecuteResult success(String id, ToolSpecification toolSpecification, String toolOutput){
        return ToolExecuteResult.builder()
                .code(1)
                .id(id)
                .toolResultType(ToolResultType.NORMAL)
                .toolSpecification(toolSpecification)
                .toolOutput(Objects.requireNonNullElse(toolOutput,""))
                .build();
    }
    public static ToolExecuteResult success(String id, ToolSpecification toolSpecification, String toolOutput, ToolResultType toolResultType){
        return ToolExecuteResult.builder()
                .code(1)
                .id(id)
                .toolResultType(toolResultType)
                .toolSpecification(toolSpecification)
                .toolOutput(Objects.requireNonNullElse(toolOutput,""))
                .build();
    }

    public static ToolExecuteResult err(String id, ToolSpecification toolSpecification, String toolOutput){
        return ToolExecuteResult.builder()
                .code(0)
                .id(id)
                .toolResultType(ToolResultType.NORMAL)
                .toolSpecification(toolSpecification)
                .toolOutput(Objects.requireNonNullElse(toolOutput,""))
                .build();
    }
    public static ToolExecuteResult err(String id, ToolSpecification toolSpecification, String toolOutput,ToolResultType toolResultType){
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
