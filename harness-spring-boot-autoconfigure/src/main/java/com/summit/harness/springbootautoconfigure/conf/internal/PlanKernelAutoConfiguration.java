package com.summit.harness.springbootautoconfigure.conf.internal;

import com.summit.core.conversation.event.RuntimeEventPublisher;
import com.summit.core.internalUtils.PlanApprovalRegistry;
import com.summit.core.internalUtils.PlanLoopHook;
import com.summit.core.plan.PlanStore;
import com.summit.harness.springbootautoconfigure.properties.tool.PlanToolProperties;
import com.summit.runtime.internalUtils.DefaultPlanApprovalRegistry;
import com.summit.runtime.internalUtils.DefaultPlanLoopHook;
import com.summit.runtime.internalUtils.DefaultPlanStore;
import com.summit.runtime.internalUtils.PlanApprovalWaiter;
import com.summit.runtime.internalUtils.PlanKernel;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Wires the plan kernel: store, approval registry, single write path, approval waiter and the agent
 * loop hook.
 *
 * <p>Every bean is {@code @ConditionalOnMissingBean}, so an application can replace the store (Redis,
 * JDBC) or the whole hook without touching the runtime. The kernel is wired even when the plan tools
 * are disabled — the hook then simply never sees a plan.</p>
 */
@AutoConfiguration
@EnableConfigurationProperties(PlanToolProperties.class)
public class PlanKernelAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public PlanStore planStore() {
        return new DefaultPlanStore();
    }

    @Bean
    @ConditionalOnMissingBean
    public PlanApprovalRegistry planApprovalRegistry() {
        return new DefaultPlanApprovalRegistry();
    }

    @Bean
    @ConditionalOnMissingBean
    public PlanKernel planKernel(PlanStore planStore, PlanApprovalRegistry planApprovalRegistry,
                                 RuntimeEventPublisher runtimeEventPublisher) {
        return new PlanKernel(planStore, planApprovalRegistry, runtimeEventPublisher);
    }

    @Bean
    @ConditionalOnMissingBean
    public PlanApprovalWaiter planApprovalWaiter(PlanApprovalRegistry planApprovalRegistry,
                                                 PlanToolProperties planToolProperties) {
        return new PlanApprovalWaiter(planApprovalRegistry, planToolProperties.getApprovalTimeoutSeconds());
    }

    @Bean
    @ConditionalOnMissingBean
    public PlanLoopHook planLoopHook(PlanKernel planKernel, PlanApprovalWaiter planApprovalWaiter) {
        return new DefaultPlanLoopHook(planKernel, planApprovalWaiter);
    }
}
