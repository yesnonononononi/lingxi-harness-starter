package com.summit.core.model.compact;


import com.summit.core.conf.ModelConfig;
import com.summit.core.model.chat.ChatModel;
import com.summit.core.model.chat.ChatModelProvider;

/** Model provider for extra tasks such as context compaction. */
public interface CompactContextModelProvider extends ChatModelProvider {

    @Override
    ChatModel create(ModelConfig config);
}
