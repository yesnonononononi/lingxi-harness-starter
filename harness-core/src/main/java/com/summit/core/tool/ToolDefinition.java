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

    /** Only policies that explicitly declare concurrency may join a concurrent batch. */
    public boolean allowConcurrent(){
        return concurrentPolicy == ConcurrentPolicy.READ_ONLY
                || concurrentPolicy == ConcurrentPolicy.ISOLATED_MUTATION;
    }
    public boolean readOnly(){
        return concurrentPolicy != null &&  concurrentPolicy.equals(ConcurrentPolicy.READ_ONLY);
    }
    /**
     * Whether this tool is admitted by a request-level tool whitelist. {@code null} and empty both
     * admit no application tool: an absent list must never widen to "every registered tool".
     *
     * <p>Framework kernel tools are admitted regardless — see {@link KernelTools}, which explains why
     * they are not the application's to grant or withhold.</p>
     */
    public boolean allowedFor(List<String> whitelist) {
        return KernelTools.isKernel(name)
                || (whitelist != null
                && whitelist.stream().anyMatch(candidate -> candidate != null && name.equals(candidate.trim())));
    }

}
