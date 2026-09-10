package com.summit.core.internalUtils;

import com.summit.core.tool.ToolExecuteResult;
import com.summit.core.tool.ToolExecution;
import com.summit.core.tool.ToolExecutor;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;

/**
 * Template of every plan kernel tool executor: the argument handling (parsing, validation, error
 * wording) is fixed in harness-core through {@link PlanToolSupport}, while the runtime subclass
 * only implements {@link #doExecute(ToolExecution)} — the actual state transition on the session
 * plan.
 *
 * <p>No plan tool ever throws at the model: argument problems and unexpected failures are returned
 * as a tool error output so the agent can correct itself in the next round.</p>
 */
@Slf4j
public abstract class AbstractPlanToolExecutor implements ToolExecutor {

    @Override
    public final @NonNull ToolExecuteResult execute(ToolExecution toolExecution) {
        try {
            return doExecute(toolExecution);
        } catch (PlanArgumentException e) {
            log.warn("【plan-tool】{} rejected: {}", name(), e.getMessage());
            return PlanToolSupport.error(toolExecution, e.getMessage());
        } catch (Exception e) {
            log.warn("【plan-tool】{} failed: {}", name(), e.getMessage());
            return PlanToolSupport.error(toolExecution, name() + " failed: " + e.getMessage());
        }
    }

    /** Applies the tool to the session plan; arguments are parsed through {@link PlanToolSupport}. */
    protected abstract ToolExecuteResult doExecute(ToolExecution toolExecution);

    /** Tool name, used by logging and error output. */
    protected abstract String name();
}
