package com.summit.core.tool;


import lombok.Builder;
import lombok.NonNull;

import java.io.Serializable;
import java.util.List;


/** Declaration of one tool exposed to the model. */
@Builder
public record ToolDefinition<T extends ToolExecutor>(
        @NonNull Integer maxOutput,
        @NonNull Long timeout,  //seconds
        @NonNull T executor,
        @NonNull Serializable id,
        @NonNull String name,
        String description,
        String parametersJsonSchema,
        ConcurrentPolicy concurrentPolicy
) {

    public boolean allowConcurrent(){
        return concurrentPolicy != null && !concurrentPolicy.equals(ConcurrentPolicy.SERIAL_MUTATION);
    }
    public boolean readOnly(){
        return concurrentPolicy != null &&  concurrentPolicy.equals(ConcurrentPolicy.READ_ONLY);
    }
    /**
     * Whether this tool is admitted by a request-level tool whitelist. {@code null} and empty both
     * admit nothing: an absent list must never widen to "every registered tool".
     */
    public boolean allowedFor(List<String> whitelist) {
        return whitelist != null
                && whitelist.stream().anyMatch(candidate -> candidate != null && name.equals(candidate.trim()));
    }

}
