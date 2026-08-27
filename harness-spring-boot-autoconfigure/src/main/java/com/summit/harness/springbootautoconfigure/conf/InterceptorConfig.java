package com.summit.harness.springbootautoconfigure.conf;

import com.summit.harnesscore.compact.Tokenizer;
import com.summit.harnesscore.interceptor.InterceptorProcessor;
import com.summit.harnesscore.tool.ToolExecution;
import com.summit.harnesscore.tool.ToolInterceptor;
import com.summit.runtime.tool.DefaultToolInterceptor;
import com.summit.runtime.DefaultInterceptorProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;

import java.util.List;

@AutoConfiguration
public class InterceptorConfig {
    @Bean
    public ToolInterceptor toolInterceptor(Tokenizer tokenizer) {
        return new DefaultToolInterceptor(tokenizer);
    }


    @Bean
    public InterceptorProcessor<ToolInterceptor, ToolExecution> interceptorProcessor(List<ToolInterceptor> toolInterceptorList){
        return new DefaultInterceptorProcessor<>(toolInterceptorList);
    }
}
