package com.summit.core.model.streaming;

public interface StreamingHandler{
    void cancel();
    boolean isCancelled();
}
