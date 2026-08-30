package com.summit.core.tool;


import lombok.Builder;
import lombok.NonNull;

import java.io.Serializable;


@Builder
public record ToolDefinition<T extends ToolExecutor>(
        @NonNull Integer maxOutput,
        @NonNull Long timeout,
        @NonNull T executor,
        @NonNull Serializable id,
        @NonNull String name,
        String description,
        String parametersJsonSchema
        ) {

}
