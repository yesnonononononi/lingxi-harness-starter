package com.summit.harness.springbootautoconfigure.conf.tool;

import com.summit.core.internalUtils.plan.PlanToolSpecs;
import com.summit.core.internalUtils.plan.PlanToolSpecs.PlanToolSpec;
import com.summit.core.tool.ToolDefinition;
import com.summit.core.tool.ToolExecutor;
import com.summit.core.tool.ToolRegistry;
import com.summit.harness.springbootautoconfigure.properties.tool.CommonToolProperties;
import com.summit.harness.springbootautoconfigure.properties.tool.PlanToolProperties;
import com.summit.runtime.coreTools.plan.CompleteTaskToolExecutor;
import com.summit.runtime.coreTools.plan.CreatePlanToolExecutor;
import com.summit.runtime.coreTools.plan.PlanKernel;
import com.summit.runtime.coreTools.plan.UpdatePlanToolExecutor;
import com.summit.runtime.coreTools.plan.UpdateTaskToolExecutor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.util.Objects;

/**
 * Registers the plan kernel tools: {@code create_plan}, {@code update_plan}, {@code update_task} and
 * {@code complete_task}.
 *
 * <p>All four are read-only with respect to the workspace (they only maintain the plan bookkeeping),
 * so they stay available under the PLANING boundary and never mark an execution as "a write tool
 * ran". {@code create_plan} is additionally {@code planningOnly}: it disappears once the loop is
 * allowed to execute, which prevents a planning tool from interrupting an implementation run.
 * {@code approve_plan} is deliberately <b>not</b> registered — only the user may approve a plan.</p>
 */
@Slf4j
@AutoConfiguration
@EnableConfigurationProperties({PlanToolProperties.class, CommonToolProperties.class})
public class PlanToolAutoConfiguration {

    private static final String ENABLED_PREFIX = "lingxi.agent.runtime.tool.plan";

    @Bean
    @ConditionalOnProperty(prefix = ENABLED_PREFIX, name = "enabled", havingValue = "true", matchIfMissing = true)
    public ToolDefinition<CreatePlanToolExecutor> createPlanToolDefinition(ToolRegistry toolRegistry, PlanKernel planKernel,
                                                                          PlanToolProperties planToolProperties,
                                                                          CommonToolProperties commonToolProperties) {
        return register(toolRegistry, PlanToolSpecs.CREATE_PLAN, new CreatePlanToolExecutor(planKernel), true,
                planToolProperties, commonToolProperties);
    }

    @Bean
    @ConditionalOnProperty(prefix = ENABLED_PREFIX, name = "enabled", havingValue = "true", matchIfMissing = true)
    public ToolDefinition<UpdatePlanToolExecutor> updatePlanToolDefinition(ToolRegistry toolRegistry, PlanKernel planKernel,
                                                                          PlanToolProperties planToolProperties,
                                                                          CommonToolProperties commonToolProperties) {
        return register(toolRegistry, PlanToolSpecs.UPDATE_PLAN, new UpdatePlanToolExecutor(planKernel), false,
                planToolProperties, commonToolProperties);
    }

    @Bean
    @ConditionalOnProperty(prefix = ENABLED_PREFIX, name = "enabled", havingValue = "true", matchIfMissing = true)
    public ToolDefinition<UpdateTaskToolExecutor> updateTaskToolDefinition(ToolRegistry toolRegistry, PlanKernel planKernel,
                                                                          PlanToolProperties planToolProperties,
                                                                          CommonToolProperties commonToolProperties) {
        return register(toolRegistry, PlanToolSpecs.UPDATE_TASK, new UpdateTaskToolExecutor(planKernel), false,
                planToolProperties, commonToolProperties);
    }

    @Bean
    @ConditionalOnProperty(prefix = ENABLED_PREFIX, name = "enabled", havingValue = "true", matchIfMissing = true)
    public ToolDefinition<CompleteTaskToolExecutor> completeTaskToolDefinition(ToolRegistry toolRegistry, PlanKernel planKernel,
                                                                              PlanToolProperties planToolProperties,
                                                                              CommonToolProperties commonToolProperties) {
        return register(toolRegistry, PlanToolSpecs.COMPLETE_TASK, new CompleteTaskToolExecutor(planKernel), false,
                planToolProperties, commonToolProperties);
    }

    /**
     * Registers one kernel tool from its single-source-of-truth {@link PlanToolSpec}.
     *
     * @param planningOnly whether the tool must be hidden once the loop is allowed to execute
     */
    private <T extends ToolExecutor> ToolDefinition<T> register(ToolRegistry toolRegistry, PlanToolSpec spec, T executor,
                                                               boolean planningOnly, PlanToolProperties planToolProperties,
                                                               CommonToolProperties commonToolProperties) {
        ToolDefinition<T> definition = ToolDefinition.<T>builder()
                .executor(executor)
                .id(spec.name())
                .name(spec.name())
                .description(spec.description())
                .parametersJsonSchema(spec.parametersJsonSchema())
                .readOnly(true)
                .planningOnly(planningOnly)
                .maxOutput(Objects.requireNonNullElseGet(planToolProperties.getMaxOutput(), commonToolProperties::getMaxOutput))
                .timeout(Objects.requireNonNullElseGet(planToolProperties.getTimeout(), commonToolProperties::getTimeout))
                .build();
        toolRegistry.register(spec.name(), definition);
        log.info("{} plan kernel tool registered (planningOnly={})", spec.name(), planningOnly);
        return definition;
    }
}
