package com.summit.harness.springbootautoconfigure.conf;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
public class JsonConfig {
    @Bean
    @ConditionalOnMissingBean
    public ObjectMapper objectMapper(){
        return new ObjectMapper();
    }
}
