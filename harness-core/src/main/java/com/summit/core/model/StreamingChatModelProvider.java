package com.summit.core.model;



public interface StreamingChatModelProvider extends ModelProvider<StreamingChatModel>{
    StreamingChatModel create(ModelConfig config);
}
