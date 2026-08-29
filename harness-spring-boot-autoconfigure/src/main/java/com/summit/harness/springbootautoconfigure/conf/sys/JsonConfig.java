package com.summit.harness.springbootautoconfigure.conf.sys;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
public class JsonConfig {
    /**
     * Plain shared mapper WITHOUT polymorphic typing: it is used to parse
     * model-generated tool call arguments, which never carry type ids. Any
     * component needing polymorphic serialization (e.g. a session store)
     * should build its own mapper.
     */
    @Bean
    @ConditionalOnMissingBean
    public ObjectMapper objectMapper(){
        return new ObjectMapper();
    }
}
