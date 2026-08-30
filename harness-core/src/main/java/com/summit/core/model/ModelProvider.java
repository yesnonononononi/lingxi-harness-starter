package com.summit.core.model;

public interface ModelProvider<T> {
    String name();
    T create(ModelConfig config);
}
