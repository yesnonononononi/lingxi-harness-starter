package com.summit.core.tool;


import com.summit.core.agent.ToolSpec;
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

    /** Evaluates both static whitelist visibility and per-round blacklist exclusion. */
    public boolean allowedFor(ToolSpec toolSpec) {
        List<String> whitelist = toolSpec == null ? null : toolSpec.getToolList();
        List<String> blacklist = toolSpec == null ? null : toolSpec.getForbiddenTools();
        return !isForbidden(blacklist) && allowedFor(whitelist);
    }

    /** Whether this tool is explicitly named in the given per-round blacklist. */
    public boolean isForbidden(List<String> blacklist) {
        return blacklist != null
                && blacklist.stream().anyMatch(candidate -> candidate != null && name.equals(candidate.trim()));
    }

}
