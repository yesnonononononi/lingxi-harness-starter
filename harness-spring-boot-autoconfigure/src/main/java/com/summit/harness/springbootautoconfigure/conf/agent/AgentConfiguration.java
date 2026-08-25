package com.summit.harness.springbootautoconfigure.conf.agent;

import com.summit.harness.springbootautoconfigure.properties.agent.AgentChatProperties;
import com.summit.harnesscore.runtime.RuntimeFactory;
import com.summit.runtime.agent.AgentConfig;
import com.summit.runtime.agent.ChatAgent;
import dev.langchain4j.model.chat.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;


@AutoConfiguration
@EnableConfigurationProperties({AgentChatProperties.class})
public class AgentConfiguration {
    @Bean
    public ChatAgent chatAgent(@Qualifier("defaultChatModel") ChatModel chatModel, RuntimeFactory defaultRuntimeFactory){
        return new ChatAgent(
                chatModel,
                defaultRuntimeFactory
        );
    }



    @Bean
    public AgentConfig agentConfig(AgentChatProperties agentProperties){
        return AgentConfig.builder()
                .squeezeThreshold(agentProperties.getSqueezeThreshold())
                .maxTokens(agentProperties.getMaxTokens())
                .maxIterations(agentProperties.getMaxIterations())
                .build();
    }




}
