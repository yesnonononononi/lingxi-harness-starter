package com.summit.core.agent;


import com.summit.core.conf.ModelConfig;
import com.summit.core.model.image.ImageGenerationOptions;
import lombok.Getter;
import lombok.experimental.SuperBuilder;
import lombok.extern.jackson.Jacksonized;

@Getter
@Jacksonized
@SuperBuilder
public class ImageRequest extends AgentRequest{
    private ModelConfig imageModelConfig;
    /** Per-call image specifications, inherited by each image invocation in this execution. */
    private ImageGenerationOptions imageGenerationOptions;
}
