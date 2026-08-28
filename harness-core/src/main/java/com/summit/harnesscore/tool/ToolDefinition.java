package com.summit.harnesscore.tool;

import dev.langchain4j.agent.tool.ToolSpecification;
import lombok.Builder;
import lombok.NonNull;


@Builder
public record ToolDefinition<T extends ToolExecutor>(
        @NonNull Integer maxOutput,
        @NonNull Long timeout,
        @NonNull T executor,
        @NonNull ToolSpecification toolSpecification) {

}
