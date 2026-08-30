package com.summit.core.model;



public interface ChatModelProvider extends ModelProvider<ChatModel>{

    ChatModel create(ModelConfig config);
}
