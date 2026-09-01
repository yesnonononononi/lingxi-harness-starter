package com.summit.core.conversation.api;

import com.summit.core.conversation.message.Message;
import com.summit.core.tool.ToolDefinition;
import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class ChatRequestEntity {
    private List<Message> messages;
    private List<ToolDefinition<?>> tools;
}
