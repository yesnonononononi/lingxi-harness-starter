package com.summit.harness.springbootautoconfigure.conf.agent;

import com.summit.harness.springbootautoconfigure.properties.agent.AgentChatProperties;
import com.summit.core.model.ModelInvoker;
import com.summit.core.runtime.RuntimeFactory;
import com.summit.runtime.agent.AgentConfig;
import com.summit.runtime.agent.ChatAgent;
import com.summit.core.model.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;


@AutoConfiguration
@EnableConfigurationProperties({AgentChatProperties.class})
public class AgentConfiguration {
    @Bean
    public ChatAgent chatAgent(@Qualifier("defaultChatModel") ChatModel chatModel, RuntimeFactory defaultRuntimeFactory, AgentConfig agentConfig, ModelInvoker defaultStreamingModelInvoker){
        return new ChatAgent(
                chatModel,
                defaultRuntimeFactory,
                agentConfig,
                defaultStreamingModelInvoker
        );
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
