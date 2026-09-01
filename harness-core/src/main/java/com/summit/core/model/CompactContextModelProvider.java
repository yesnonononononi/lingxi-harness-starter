package com.summit.core.model;


/** 上下文压缩等额外任务专用模型 Provider（默认无 thinking）。 */
public interface CompactContextModelProvider extends ChatModelProvider {

    @Override
    ChatModel create(ModelConfig config);
}
