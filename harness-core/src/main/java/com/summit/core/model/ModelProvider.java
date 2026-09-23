package com.summit.core.model;

import com.summit.core.conf.ModelConfig;

public interface ModelProvider<T> {
    String name();
    T create(ModelConfig config);
}
