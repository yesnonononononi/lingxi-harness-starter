package com.summit.adapter.langchain4j.model;

import com.summit.core.adapter.MessageCodec;
import com.summit.core.adapter.ToolCodec;
import com.summit.core.conversation.api.ChatRequestEntity;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;

import java.util.List;

public class ChatRequestBuilder {
 static ChatRequest buildRequest(ChatRequestEntity request, MessageCodec< ChatMessage, ChatResponse > messageCodec, ToolCodec< ToolSpecification > toolCodec) {
        ChatRequest.Builder builder = ChatRequest.builder()
                .messages(messageCodec.toFramework(request.getMessages()));
        if (request.getTools() != null && !request.getTools().isEmpty()) {
            List<ToolSpecification> toolSpecifications = request.getTools().stream()
                    .map(toolCodec::toFrameworkTool)
                    .toList();
            builder.toolSpecifications(toolSpecifications);
        }
        return builder.build();
    }

}
