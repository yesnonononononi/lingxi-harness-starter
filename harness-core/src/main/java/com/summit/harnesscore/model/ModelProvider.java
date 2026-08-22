package com.summit.harnesscore.model;

public interface ModelProvider<T> {
    String name();
    T create(ModelConfig config);
}
