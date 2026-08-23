package com.summit.harnesscore.model;

import lombok.Builder;
import lombok.Data;

import java.time.Duration;

@Data
@Builder
public class ModelConfig {
    private String baseUrl;
    private String apiKey;
    private String modelName;
    private String provider;
    private Duration timeout;
}
