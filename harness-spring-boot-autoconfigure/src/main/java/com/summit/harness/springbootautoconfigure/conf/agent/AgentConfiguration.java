package com.summit.harness.springbootautoconfigure.conf.agent;

import com.summit.core.conf.ModelConfig;
import com.summit.harness.springbootautoconfigure.properties.agent.AgentChatProperties;
import com.summit.core.model.RequestModelInvokerFactory;
import com.summit.core.runtime.RuntimeFactory;
import com.summit.core.runtime.loop.ExecutionControl;
import com.summit.core.runtime.loop.ExecutionRepository;
import com.summit.core.workspace.WorkspaceManager;
import com.summit.runtime.agent.AgentConfig;
import com.summit.runtime.agent.ChatAgent;
import com.summit.runtime.agent.DefaultChatAgent;
import com.summit.runtime.loop.DefaultExecutionController;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;


@AutoConfiguration
@EnableConfigurationProperties({AgentChatProperties.class})
public class AgentConfiguration {
    @Bean
    @ConditionalOnMissingBean(ChatAgent.class)
    public DefaultChatAgent defaultChatAgent(RuntimeFactory defaultRuntimeFactory,
                                             RequestModelInvokerFactory modelInvokerFactory,
                                             WorkspaceManager workspaceManager,
                                             @Qualifier("chatModelConfig") ModelConfig modelConfig){
        return new DefaultChatAgent(
                defaultRuntimeFactory,
                modelInvokerFactory,
                workspaceManager,
                modelConfig
        );
    }

    @Bean
    @ConditionalOnMissingBean(ExecutionControl.class)
    public ExecutionControl executionControl(ChatAgent agent,
                                             ExecutionRepository executionRepository) {
        return new DefaultExecutionController(agent, executionRepository);
    }



    @Bean
    public AgentConfig agentConfig(AgentChatProperties agentProperties){
        return AgentConfig.builder()
                .squeezeThreshold(new AgentConfig.ProgressiveSqueezePolicy(
                        new AgentConfig.OriginalSqueeze(
                                agentProperties.getTruncateSqueezeThreshold(),
                                agentProperties.getExpectTruncateTurn()),
                        new AgentConfig.ModelSqueeze(
                                agentProperties.getModelSqueezeThreshold())))
                .maxTokens(agentProperties.getMaxTokens())
                .maxIterations(agentProperties.getMaxIterations())
                .systemPrompt(agentProperties.getSystemPrompt())
                .build();
    }




}
