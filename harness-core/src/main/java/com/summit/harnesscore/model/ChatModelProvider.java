package com.summit.harnesscore.model;



public interface ChatModelProvider extends ModelProvider<ChatModel>{

    ChatModel create(ModelConfig config);
}
