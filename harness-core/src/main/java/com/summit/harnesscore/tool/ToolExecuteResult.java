package com.summit.harnesscore.tool;

import dev.langchain4j.agent.tool.ToolSpecification;
import lombok.Builder;
import lombok.Data;
@Builder
@Data
public class ToolExecuteResult {
    private Integer code;
    private String id;
    private ToolSpecification toolSpecification;
    private String toolOutput;
    public static ToolExecuteResult success(String id, ToolSpecification toolSpecification, String toolOutput){
        return ToolExecuteResult.builder()
                .code(1)
                .id(id)
                .toolSpecification(toolSpecification)
                .toolOutput(toolOutput)
                .build();
    }

    public static ToolExecuteResult err(String id, ToolSpecification toolSpecification, String toolOutput){
        return ToolExecuteResult.builder()
                .code(0)
                .id(id)
                .toolSpecification(toolSpecification)
                .toolOutput(toolOutput)
                .build();
    }
}
