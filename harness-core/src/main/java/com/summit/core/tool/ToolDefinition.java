package com.summit.core.tool;


import lombok.Builder;
import lombok.NonNull;

import java.io.Serializable;


/**
 * Declaration of one tool exposed to the model.
 *
 * @param maxOutput           maximum number of characters returned to the model
 * @param timeout             execution timeout in seconds
 * @param executor            executor implementing the tool
 * @param id                  stable tool id
 * @param name                tool name used by the model
 * @param description         model-facing description (behaviour, when to call it)
 * @param parametersJsonSchema JSON schema of the arguments
 * @param readOnly            whether the tool leaves the workspace untouched; read-only tools stay
 *                            available under the PLANING boundary and never mark an execution as
 *                            "a write tool ran"
 * @param planningOnly        whether the tool belongs to the planning phase only. Such tools are
 *                            hidden from the model as soon as the loop runs under the EXECUTE
 *                            boundary, so planning-specific tools (e.g. {@code create_plan}) can
 *                            never interfere with an implementation run.
 */
@Builder
public record ToolDefinition<T extends ToolExecutor>(
        @NonNull Integer maxOutput,
        @NonNull Long timeout,
        @NonNull T executor,
        @NonNull Serializable id,
        @NonNull String name,
        String description,
        String parametersJsonSchema,
        boolean readOnly,
        boolean planningOnly
) {

}
