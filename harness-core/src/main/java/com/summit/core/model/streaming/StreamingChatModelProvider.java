package com.summit.core.model.streaming;


import com.summit.core.conf.ModelConfig;
import com.summit.core.model.ModelProvider;

public interface StreamingChatModelProvider extends ModelProvider<StreamingChatModel> {
    StreamingChatModel create(ModelConfig config);
}
