package com.summit.runtime;

import com.summit.core.agent.AgentRequest;
import com.summit.core.agent.Execution;
import com.summit.core.tool.LoopBoundary;

/**
 * Single source of truth for the loop boundary in effect on one round.
 *
 * <p>Two call sites must agree on it — the tools exposed to the model and the boundary the tool
 * executor enforces. Keeping the rule in one place stops the two from drifting apart (they used to
 * duplicate the same ternary).</p>
 */
public final class LoopBoundaryResolver {

    private LoopBoundaryResolver() {
    }

    /**
     * The boundary the current round runs under: an approved plan forces {@link LoopBoundary#EXECUTE},
     * otherwise the boundary requested by the {@link AgentRequest} applies. An absent request or
     * boundary is treated as executable by {@link LoopBoundary#allowExecute(LoopBoundary)}.
     *
     * @param planApproved true once the plan lifecycle approved the plan being implemented
     */
    public static LoopBoundary resolve(Execution execution, boolean planApproved) {
        if (planApproved) {
            return LoopBoundary.EXECUTE;
        }
        AgentRequest request = execution.getAgentRequest();
        return request == null ? null : request.getLoopBoundary();
    }
}
