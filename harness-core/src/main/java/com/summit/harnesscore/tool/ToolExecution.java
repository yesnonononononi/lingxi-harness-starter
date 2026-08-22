package com.summit.harnesscore.tool;

import com.summit.harnesscore.runtime.Workspace;
import dev.langchain4j.agent.tool.ToolSpecification;
import lombok.Builder;
import lombok.Data;
@Builder
@Data
public class ToolExecution {
    private String id;
    private ToolSpecification toolSpecification;
    private String args;
    private Workspace workspace;
}
