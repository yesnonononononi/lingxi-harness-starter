package com.summit.harness.springbootautoconfigure.conf.sys;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.summit.harnesscore.compact.Tokenizer;
import com.summit.harnesscore.conversation.ConversationManager;
import com.summit.harnesscore.conversation.event.RuntimeEventPublisher;
import com.summit.harnesscore.runtime.RuntimeExecutionPolicy;
import com.summit.harnesscore.runtime.RuntimeFactory;
import com.summit.harnesscore.runtime.RuntimeListener;
import com.summit.harnesscore.runtime.Workspace;
import com.summit.harnesscore.tool.ToolExecutionManager;
import com.summit.runtime.agent.AgentConfig;
import com.summit.runtime.policy.DefaultRuntimeExecutionPolicy;
import com.summit.runtime.agent.DefaultRuntimeFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;

import java.util.List;

@AutoConfiguration
public class ExecutionRuntimeConfig {
    @Bean
    public RuntimeFactory defaultRuntimeFactory(Workspace workspace, RuntimeEventPublisher defaultRuntimeListener, ConversationManager conversationManager, ToolExecutionManager defaultToolExecutionManager, AgentConfig agentConfig, ObjectMapper objectMapper, Tokenizer tokenizer){
        return DefaultRuntimeFactory.builder()
                .workspace(workspace)
                .runtimeExecutionPolicy(runtimeExecutionPolicy(agentConfig, tokenizer))
                .toolExecutionManager(defaultToolExecutionManager)
                .runtimeEventPublisher(defaultRuntimeListener)
                .conversationManager(conversationManager)
                .objectMapper(objectMapper)
                .maxIterations(agentConfig.maxIterations())
                .build();
    }


    @Bean
    public RuntimeExecutionPolicy runtimeExecutionPolicy(AgentConfig agentConfig, Tokenizer tokenizer){
        return new DefaultRuntimeExecutionPolicy(agentConfig,tokenizer);
    }

    @Bean
    public RuntimeEventPublisher defaultRuntimeListener(List<RuntimeListener> listeners){
        return new RuntimeEventPublisher(listeners);
    }
}
