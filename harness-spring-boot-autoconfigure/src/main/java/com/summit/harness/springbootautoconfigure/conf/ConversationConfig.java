package com.summit.harness.springbootautoconfigure.conf;

import com.summit.harnesscore.conversation.ConversationManager;
import com.summit.harnesscore.conversation.event.RuntimeEventPublisher;
import com.summit.harnesscore.runtime.Workspace;
import com.summit.runtime.conversation.DefaultConversationManager;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
public class ConversationConfig {
    @Bean
    public ConversationManager defaultConversationManager (Workspace workspace, RuntimeEventPublisher runtimeEventPublisher){
        return new DefaultConversationManager(workspace,runtimeEventPublisher);
    }
}
