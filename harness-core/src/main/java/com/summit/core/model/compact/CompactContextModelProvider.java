package com.summit.core.model.compact;


import com.summit.core.model.ChatModel;
import com.summit.core.model.ChatModelProvider;
import com.summit.core.model.ModelConfig;

/** 上下文压缩等额外任务专用模型 Provider（默认无 thinking）。 */
public interface CompactContextModelProvider extends ChatModelProvider {

    @Override
    ChatModel create(ModelConfig config);
}
