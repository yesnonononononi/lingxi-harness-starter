package com.summit.harness.springbootautoconfigure.conf;

import com.summit.harness.springbootautoconfigure.properties.AgentChatProperties;
import com.summit.harness.springbootautoconfigure.properties.AgentProperties;
import com.summit.harnesscore.model.ChatModelProvider;
import com.summit.harnesscore.model.ModelConfig;
import com.summit.harnesscore.model.ModelProvider;
import com.summit.harnesscore.model.ModelProviderRegistry;
import com.summit.harnesscore.runtime.Workspace;
import com.summit.harnesscore.tool.ToolRegistry;
import com.summit.runtime.agent.ChatAgent;
import com.summit.runtime.provider.DefaultChatModelProvider;
import com.summit.runtime.provider.DefaultStreamingModelProvider;
import com.summit.runtime.workspace.LocalWorkSpace;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.util.List;

@AutoConfiguration
@EnableConfigurationProperties({AgentProperties.class, AgentChatProperties.class})
public class AgentConfig {
    @Bean
    public ChatAgent chatAgent(ChatModel chatModel, ToolRegistry toolRegistry, Workspace localWorkSpace){
        return new ChatAgent(chatModel, localWorkSpace,toolRegistry);
    }


}
