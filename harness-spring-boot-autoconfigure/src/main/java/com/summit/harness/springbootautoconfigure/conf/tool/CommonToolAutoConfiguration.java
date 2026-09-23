package com.summit.harness.springbootautoconfigure.conf.tool;

import com.summit.core.conversation.event.RuntimeEventPublisher;
import com.summit.core.interceptor.InterceptorProcessor;
import com.summit.core.tool.*;
import com.summit.runtime.toolSupport.DefaultToolExecutionManager;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import java.util.List;

@AutoConfiguration
public class CommonToolAutoConfiguration {
    @Bean
    public ToolRegistry toolRegistry(List<ToolDefinition<? extends ToolExecutor>> list) {
        return new ToolRegistry(list);
    }

    @Bean
    public ToolExecutionManager defaultToolExecutionManager(ToolRegistry toolRegistry, RuntimeEventPublisher runtimeEventPublisher, InterceptorProcessor<ToolExecution> interceptorProcessor, List<ToolExecutionPolicy> executionPolicies) {
        return new DefaultToolExecutionManager(
                ToolExecutionContext.builder()
                        .toolRegistry(toolRegistry)
                        .runtimeEventPublisher(runtimeEventPublisher)
                        .build(),
                interceptorProcessor,
                executionPolicies
        );
    }
}
