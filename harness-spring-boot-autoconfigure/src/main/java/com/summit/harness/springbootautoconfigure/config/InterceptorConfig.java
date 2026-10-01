package com.summit.harness.springbootautoconfigure.config;

import com.summit.core.compact.Tokenizer;
import com.summit.core.interceptor.InterceptorProcessor;
import com.summit.core.tool.ToolExecution;
import com.summit.core.tool.ToolInterceptor;
import com.summit.runtime.interceptor.DefaultInterceptorProcessor;
import com.summit.runtime.tool.DefaultToolInterceptor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

import java.util.List;

@AutoConfiguration
public class InterceptorConfig {

    /**
     * The default tool interceptor (result truncation by {@code ToolDefinition.maxOutput}).
     *
     * <p>Gated by name rather than by type on purpose: {@code ToolInterceptor} is a collected list
     * ({@code List<ToolInterceptor>}), so a type-level {@code @ConditionalOnMissingBean} would drop
     * this default the moment the application contributes any interceptor of its own — silently
     * losing result truncation. The name guard lets a default be replaced explicitly while still
     * letting additional interceptors coexist.</p>
     */
    @Bean
    @ConditionalOnMissingBean(name = "toolInterceptor")
    public ToolInterceptor toolInterceptor(Tokenizer tokenizer) {
        return new DefaultToolInterceptor(tokenizer);
    }

    @Bean
    @ConditionalOnMissingBean
    public InterceptorProcessor<ToolExecution> interceptorProcessor(List<ToolInterceptor> toolInterceptorList) {
        return new DefaultInterceptorProcessor<>(toolInterceptorList);
    }
}
