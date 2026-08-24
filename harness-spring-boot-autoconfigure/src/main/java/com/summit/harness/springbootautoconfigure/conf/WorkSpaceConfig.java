package com.summit.harness.springbootautoconfigure.conf;

import com.summit.harnesscore.runtime.OsType;
import com.summit.harnesscore.runtime.RuntimeEnvironment;
import com.summit.harnesscore.runtime.ShellType;
import com.summit.harnesscore.runtime.Workspace;
import com.summit.runtime.workspace.LocalWorkSpace;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import java.nio.charset.StandardCharsets;

@AutoConfiguration
public class WorkSpaceConfig {
    @Bean
    @ConditionalOnMissingBean
    public Workspace localWorkSpace(){
        return new LocalWorkSpace(
                RuntimeEnvironment.builder()
                        .workDir(System.getProperty("user.dir"))
                        .osType(OsType.WINDOWS)
                        .shellType(ShellType.PWSH)
                        .charset(StandardCharsets.UTF_8)
                        .build()
        );
    }
}
