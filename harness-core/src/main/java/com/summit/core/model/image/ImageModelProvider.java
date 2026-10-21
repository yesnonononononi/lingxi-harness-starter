package com.summit.core.model.image;

import com.summit.core.conf.ModelConfig;
import com.summit.core.model.ModelProvider;

public interface ImageModelProvider extends ModelProvider<ImageModel> {
    @Override
    ImageModel create(ModelConfig config);
}
