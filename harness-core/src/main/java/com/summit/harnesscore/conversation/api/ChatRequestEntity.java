package com.summit.harnesscore.conversation.api;

import com.summit.harnesscore.conversation.message.Message;
import com.summit.harnesscore.tool.ToolDefinition;
import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class ChatRequestEntity {
    private List<Message> messages;
    private List<ToolDefinition<?>> tools;
}
