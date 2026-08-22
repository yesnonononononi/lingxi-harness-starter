package com.summit.harness.springbootautoconfigure.conf;

import com.summit.harness.springbootautoconfigure.properties.AgentChatProperties;
import com.summit.harness.springbootautoconfigure.properties.AgentProperties;
import com.summit.harnesscore.model.ModelProvider;
import com.summit.harnesscore.model.ModelProviderRegistry;
import com.summit.runtime.provider.DefaultChatModelProvider;
import com.summit.runtime.provider.DefaultStreamingModelProvider;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.util.List;

@AutoConfiguration
@EnableConfigurationProperties({AgentProperties.class, AgentChatProperties.class})
public class ModelProviderConfig {

    @Bean
    public ModelProvider<StreamingChatModel> streamingChatModelModelProvider(){
        return new DefaultStreamingModelProvider();
    }

    @Bean
    public ModelProvider<ChatModel> chatModelModelProvider(){
        return new DefaultChatModelProvider();
    }


    @Bean
    public ModelProviderRegistry<StreamingChatModel> streamingModelProviderRegistry(List<ModelProvider<StreamingChatModel>> providers){
        ModelProviderRegistry<StreamingChatModel> registry = new ModelProviderRegistry<>();
        providers.forEach(registry::register);
        return registry;
    }
    @Bean
    public ModelProviderRegistry<ChatModel> chatModelProviderRegistry(List<ModelProvider<ChatModel>> providers){
        ModelProviderRegistry<ChatModel > registry = new ModelProviderRegistry<>();
        providers.forEach(registry::register);
        return registry;
    }
}
