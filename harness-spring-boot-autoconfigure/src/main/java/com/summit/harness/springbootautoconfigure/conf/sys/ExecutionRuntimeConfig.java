package com.summit.harness.springbootautoconfigure.conf.sys;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.summit.harnesscore.compact.Tokenizer;
import com.summit.harnesscore.conversation.ConversationManager;
import com.summit.harnesscore.conversation.event.RuntimeEventPublisher;
import com.summit.harnesscore.runtime.RuntimeExecutionPolicy;
import com.summit.harnesscore.runtime.RuntimeFactory;
import com.summit.harnesscore.runtime.RuntimeListener;
import com.summit.harnesscore.tool.ToolExecutionManager;
import com.summit.runtime.agent.AgentConfig;
import com.summit.runtime.policy.DefaultRuntimeExecutionPolicy;
import com.summit.runtime.DefaultRuntimeFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

import java.util.List;

@AutoConfiguration
public class ExecutionRuntimeConfig {
    @Bean
    @ConditionalOnMissingBean
    public RuntimeFactory defaultRuntimeFactory(RuntimeEventPublisher defaultRuntimeListener, ConversationManager conversationManager, ToolExecutionManager defaultToolExecutionManager, AgentConfig agentConfig, ObjectMapper objectMapper, RuntimeExecutionPolicy runtimeExecutionPolicy){
        return DefaultRuntimeFactory.builder()
                .runtimeExecutionPolicy(runtimeExecutionPolicy)
                .toolExecutionManager(defaultToolExecutionManager)
                .runtimeEventPublisher(defaultRuntimeListener)
                .conversationManager(conversationManager)
                .objectMapper(objectMapper)
                .maxIterations(agentConfig.maxIterations())
                .build();
    }


    @Bean
    @ConditionalOnMissingBean
    public RuntimeExecutionPolicy runtimeExecutionPolicy(AgentConfig agentConfig, Tokenizer tokenizer){
        return new DefaultRuntimeExecutionPolicy(agentConfig,tokenizer);
    }

    @Bean
    public RuntimeEventPublisher defaultRuntimeListener(List<RuntimeListener> listeners){
        return new RuntimeEventPublisher(listeners);
    }
}
