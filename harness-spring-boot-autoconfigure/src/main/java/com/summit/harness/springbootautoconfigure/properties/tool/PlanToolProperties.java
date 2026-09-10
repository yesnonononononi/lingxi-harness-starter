package com.summit.harness.springbootautoconfigure.properties.tool;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings of the plan kernel tools ({@code create_plan} / {@code update_plan} / {@code update_task}
 * / {@code complete_task}) and of the human approval gate.
 */
@Data
@ConfigurationProperties(prefix = "lingxi.agent.runtime.tool.plan")
public class PlanToolProperties {

    /** Whether the plan kernel tools are registered at all. */
    private boolean enabled = true;

    /** Optional output cap override; falls back to {@code lingxi.agent.runtime.tool.common.max-output}. */
    private Integer maxOutput;

    /** Optional timeout override in seconds; falls back to {@code ...tool.common.timeout}. */
    private Long timeout;

    /** How long the agent loop waits for the human plan decision before treating it as a rejection. */
    private long approvalTimeoutSeconds = 600;
}
