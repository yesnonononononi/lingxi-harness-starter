package com.summit.harness.springbootautoconfigure.conf;

import com.summit.harnesscore.runtime.Workspace;
import com.summit.runtime.workspace.LocalWorkSpace;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
public class WorkSpaceConfig {
    @Bean
    public Workspace localWorkSpace(){
        return new LocalWorkSpace(System.getProperty("user.dir"));
    }
}
