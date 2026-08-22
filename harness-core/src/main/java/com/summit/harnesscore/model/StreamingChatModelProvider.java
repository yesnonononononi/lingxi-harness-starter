package com.summit.harnesscore.model;


import dev.langchain4j.model.chat.StreamingChatModel;

public interface StreamingChatModelProvider extends ModelProvider<StreamingChatModel>{
    StreamingChatModel create(ModelConfig config);
}
