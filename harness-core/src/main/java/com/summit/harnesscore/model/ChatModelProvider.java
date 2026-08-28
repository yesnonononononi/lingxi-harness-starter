package com.summit.harnesscore.model;


import dev.langchain4j.model.chat.ChatModel;

public interface ChatModelProvider extends ModelProvider<ChatModel>{

    ChatModel create(ModelConfig config);
}
