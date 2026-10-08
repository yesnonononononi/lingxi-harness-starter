package com.summit.harness.springbootautoconfigure.config.tool;

import com.summit.core.conversation.event.RuntimeEventPublisher;
import com.summit.core.interceptor.InterceptorProcessor;
import com.summit.core.tool.*;
import com.summit.harness.springbootautoconfigure.properties.tool.ToolProperties;
import com.summit.runtime.tool.DefaultToolExecutionManager;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import java.util.List;
@EnableConfigurationProperties(ToolProperties.class)
@AutoConfiguration
public class CommonToolAutoConfiguration {

    /**
     * The process-wide tool registry, built from every {@code ToolDefinition} bean.
     *
     * <p>Gated by name: the parameter is a collected list, so a type-level condition would let a
     * single application-defined registry suppress the framework one that the whole tool pipeline
     * depends on. Named gating keeps the default present while allowing an explicit replacement.</p>
     */
    @Bean
    @ConditionalOnMissingBean(name = "toolRegistry")
    public ToolRegistry toolRegistry(List<ToolDefinition<? extends ToolExecutor>> list) {
        return new ToolRegistry(list);
    }




    @Bean
    @ConditionalOnMissingBean(ToolExecutionManager.class)
    public ToolExecutionManager defaultToolExecutionManager(ToolRegistry toolRegistry, RuntimeEventPublisher runtimeEventPublisher, InterceptorProcessor<ToolExecution> interceptorProcessor, List<ToolExecutionPolicy> executionPolicies, ToolProperties toolProperties) {
        return new DefaultToolExecutionManager(
                ToolExecutionContext.builder()
                        .toolRegistry(toolRegistry)
                        .concurrentToolLimit(toolProperties.concurrentToolLimit())
                        .runtimeEventPublisher(runtimeEventPublisher)
                        .build(),
                interceptorProcessor,
                executionPolicies
        );
    }
}
