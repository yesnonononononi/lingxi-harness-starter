package com.summit.core.model.chat;


import com.summit.core.conf.ModelConfig;
import com.summit.core.model.ModelProvider;

public interface ChatModelProvider extends ModelProvider<ChatModel> {

    ChatModel create(ModelConfig config);

}
