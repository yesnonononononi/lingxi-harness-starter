package com.summit.harnesscore.model;

import dev.langchain4j.model.chat.request.ChatRequest;
@FunctionalInterface
public interface StreamingModelInvoker {

    <T> void invoke(
            ChatRequest request
         );

}
