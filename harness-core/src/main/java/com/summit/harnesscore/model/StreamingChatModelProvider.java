package com.summit.harnesscore.model;



public interface StreamingChatModelProvider extends ModelProvider<StreamingChatModel>{
    StreamingChatModel create(ModelConfig config);
}
