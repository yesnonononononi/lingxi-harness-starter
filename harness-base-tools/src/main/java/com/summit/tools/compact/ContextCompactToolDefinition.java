package com.summit.tools.compact;

import com.summit.harnesscore.tool.Tool;
import com.summit.harnesscore.tool.ToolExecutor;
import dev.langchain4j.model.chat.ChatModel;
import lombok.AllArgsConstructor;

@AllArgsConstructor
public class ContextCompactToolDefinition implements Tool {
    private final ChatModel chatModel;
    @Override
    public String name() {
        return "context_compact";
    }

    @Override
    public String id() {
        return String.valueOf("context_compact".hashCode());
    }

    @Override
    public ToolExecutor executor() {
        return new ContextCompactToolExecutor(chatModel);
    }
}
