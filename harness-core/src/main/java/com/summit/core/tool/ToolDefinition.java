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
     * Whether this tool belongs in the model-facing declarations of a request carrying this
     * whitelist — the only test that decides what the provider is told, so a run's tool list stays
     * identical round after round and its prefix cache survives.
     *
     * <p>{@code null} and empty both admit no application tool: an absent list must never widen to
     * "every registered tool". Framework kernel tools are admitted regardless — see
     * {@link KernelTools}, which explains why they are not the application's to grant or withhold.</p>
     */
    public boolean visibleToModel(List<String> whitelist) {
        return KernelTools.isKernel(name)
                || (whitelist != null
                && whitelist.stream().anyMatch(candidate -> candidate != null && name.equals(candidate.trim())));
    }

    /**
     * Whether this tool may actually run: visible under the whitelist and not named by the
     * per-round blacklist. Execution is the only thing the blacklist touches — it never changes
     * {@link #visibleToModel(List)}, which is what keeps the prompt prefix cacheable.
     */
    public boolean executableUnder(ToolSpec toolSpec) {
        List<String> whitelist = toolSpec == null ? null : toolSpec.getToolList();
        return !forbiddenBy(toolSpec) && visibleToModel(whitelist);
    }

    /** Whether the request's per-round blacklist names this tool. */
    public boolean forbiddenBy(ToolSpec toolSpec) {
        return toolSpec != null && names(toolSpec.getForbiddenTools());
    }

    private boolean names(List<String> candidates) {
        return candidates != null
                && candidates.stream().anyMatch(candidate -> candidate != null && name.equals(candidate.trim()));
    }

}
