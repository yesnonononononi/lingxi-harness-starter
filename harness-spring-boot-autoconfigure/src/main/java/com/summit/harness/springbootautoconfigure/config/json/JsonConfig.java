package com.summit.harness.springbootautoconfigure.config.json;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.summit.core.json.ExecutionJson;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
public class JsonConfig {
    /**
     * Shared mapper without global default typing. Execution snapshots use explicit
     * message/workspace type names; ordinary tool arguments do not need type ids.
     * Applications with custom workspace implementations can supply their own mapper
     * through ExecutionJson.newObjectMapper with named workspace subtypes.
     */
    @Bean
    @ConditionalOnMissingBean
    public ObjectMapper objectMapper() {
        return ExecutionJson.newObjectMapper();
    }
}
