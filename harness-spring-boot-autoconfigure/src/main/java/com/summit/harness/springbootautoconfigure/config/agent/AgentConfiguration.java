package com.summit.harness.springbootautoconfigure.config.agent;

import com.summit.core.conf.ModelConfig;
import com.summit.core.conversation.event.RuntimeEventPublisher;
import com.summit.core.mcp.ScopeMcpProvider;
import com.summit.harness.springbootautoconfigure.properties.agent.AgentChatProperties;
import com.summit.core.model.RequestModelInvokerFactory;
import com.summit.core.runtime.RuntimeFactory;
import com.summit.core.runtime.loop.ExecutionControl;
import com.summit.core.runtime.loop.ExecutionRepository;
import com.summit.core.runtime.loop.lifestyle.RuntimeLifeStyleManager;
import com.summit.core.workspace.WorkspaceManager;
import com.summit.runtime.agent.AgentConfig;
import com.summit.runtime.agent.ChatAgent;
import com.summit.runtime.agent.DefaultChatAgent;
import com.summit.runtime.loop.DefaultExecutionController;
import org.springframework.beans.factory.ObjectProvider;
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
                                             @Qualifier("chatModelConfig") ModelConfig modelConfig,
                                             ScopeMcpProvider scopeMcpProvider,
                                             ExecutionRepository executionRepository,
                                             ExecutionControl executionControl) {
        return new DefaultChatAgent(
                defaultRuntimeFactory,
                modelInvokerFactory,
                workspaceManager,
                modelConfig,
                scopeMcpProvider,
                executionRepository,
                executionControl
        );
    }

    @Bean
    @ConditionalOnMissingBean(ExecutionControl.class)
    public ExecutionControl executionControl(ObjectProvider<ChatAgent> agent,
                                             ExecutionRepository executionRepository,
                                             RuntimeEventPublisher runtimeEvents,
                                             RuntimeLifeStyleManager runtimeLifeStyleManager) {
        return new DefaultExecutionController(agent::getObject, executionRepository, runtimeEvents, runtimeLifeStyleManager);
    }

    /**
     * The squeeze thresholds are not read here.
     *
     * <p>They are published as a {@link AgentConfig.ProgressiveSqueezePolicy} bean by the module that
     * owns compaction ({@code harness-kernel-tools}), and consumed through an {@code ObjectProvider}
     * so this module needs no dependency on it. A run without that module gets a null policy and the
     * runtime's own fallback bands — which is the right answer for a deployment that has no
     * compaction configured at all.</p>
     */
    @Bean
    @ConditionalOnMissingBean(AgentConfig.class)
    public AgentConfig agentConfig(AgentChatProperties agentProperties,
                                   ObjectProvider<AgentConfig.ProgressiveSqueezePolicy> squeezePolicy) {
        return AgentConfig.builder()
                .squeezeThreshold(squeezePolicy.getIfAvailable())
                .maxTokens(agentProperties.getMaxTokens())
                .maxIterations(agentProperties.getMaxIterations())
                .maxConsecutiveCompactions(agentProperties.getMaxConsecutiveCompactions())
                .build();
    }
}
