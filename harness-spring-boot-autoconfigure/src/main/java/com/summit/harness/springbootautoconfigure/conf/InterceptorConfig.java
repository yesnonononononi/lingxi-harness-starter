package com.summit.harness.springbootautoconfigure.conf;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.summit.core.compact.Tokenizer;
import com.summit.core.conversation.event.RuntimeEventPublisher;
import com.summit.core.interceptor.InterceptorProcessor;
import com.summit.core.tool.CommandConfirmGate;
import com.summit.core.tool.CommandDecision;
import com.summit.core.tool.DecideRegistry;
import com.summit.core.tool.ToolExecution;
import com.summit.core.tool.ToolInterceptor;
import com.summit.runtime.toolSupport.DefaultToolInterceptor;
import com.summit.runtime.lifeStyle.DefaultInterceptorProcessor;
import com.summit.tools.terminal.CommandApprovalToolInterceptor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;

import java.util.List;

@AutoConfiguration
public class InterceptorConfig {

    @Bean
    public ToolInterceptor toolInterceptor(Tokenizer tokenizer) {
        return new DefaultToolInterceptor(tokenizer);
    }

    /**
     * Human-approval adjudicator for the command tool: inside the generic interceptor
     * chain and before the command runs, it decides whether human confirmation is
     * required (PRE_EXEC_CONFIRM / DANGEROUS_BLOCK), fully decoupled from the
     * command executor.
     */
    @Bean
    public ToolInterceptor commandApprovalToolInterceptor(ObjectMapper objectMapper, RuntimeEventPublisher runtimeEventPublisher, DecideRegistry<CommandConfirmGate, CommandDecision> commandConfirmRegistry) {
        return new CommandApprovalToolInterceptor(objectMapper, runtimeEventPublisher, commandConfirmRegistry);
    }


    @Bean
    public InterceptorProcessor<ToolExecution> interceptorProcessor(List<ToolInterceptor> toolInterceptorList){
        return new DefaultInterceptorProcessor<>(toolInterceptorList);
    }
}
