package com.summit.core.agent;

import com.summit.core.tool.CommandConfirmLevel;
import com.summit.core.tool.LoopBoundary;
import lombok.Builder;
import lombok.Data;

/**
 * Per-run controls for an agent request.
 *
 * <p>Keeping execution policy in a nested value object prevents {@link AgentRequest}
 * from growing every time the runtime gains another switch.</p>
 */
@Data
@Builder
public class AgentRuntimeParameters {

    @Builder.Default
    private LoopBoundary loopBoundary = LoopBoundary.EXECUTE;

    /**
     * Approval level for command-line tools. A {@code null} value preserves the
     * executor's backwards-compatible {@code FULL_ACCESS} behaviour.
     */
    private CommandConfirmLevel confirmLevel;


}
