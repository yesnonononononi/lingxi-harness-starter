package com.summit.harnessexample;

import com.summit.core.runtime.Workspace;
import com.summit.runtime.sandbox.DockerWorkspace;


import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.UUID;

@Configuration
public class WorkspaceConfig {

    @Bean
    @ConditionalOnProperty(prefix = "lingxi.agent",name = "workspace",havingValue = "docker")
    public Workspace dockerWorkspace(){
        return new DockerWorkspace(
                UUID.randomUUID().toString(),
                "6939c6277d8f3b4074f2ec1d8315fe5001b59b21357462efb1011350ef90030e",
                "/app"

        );
    }
}
