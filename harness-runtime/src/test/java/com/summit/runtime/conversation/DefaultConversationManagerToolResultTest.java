package com.summit.runtime.conversation;

import com.summit.core.agent.AgentRequest;
import com.summit.core.agent.Execution;
import com.summit.core.compact.ContextAttachmentProvider;
import com.summit.core.conversation.api.ChatResponseEntity;
import com.summit.core.conversation.message.AiMessageEntity;
import com.summit.core.conversation.message.ToolMessageEntity;
import com.summit.core.runtime.loop.LoopMessages;
import com.summit.core.tool.ToolDefinition;
import com.summit.core.tool.ToolExecuteResult;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DefaultConversationManagerToolResultTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void recordsTextOutputAndFailureStatus(boolean error) {
        ToolExecuteResult result = error ? ToolExecuteResult.err("tool result") : ToolExecuteResult.success("tool result");
        result.setId("call-1");
        result.setToolSpecification(ToolDefinition.builder().id("lookup").name("lookup")
                .timeout(5L).maxOutput(100).executor(call -> ToolExecuteResult.success("unused")).build());
        Execution execution = Execution.builder().id("execution-1").agentRequest(AgentRequest.builder().build())
                .messages(new ArrayList<>()).build();
        AiMessageEntity ai = AiMessageEntity.builder().text("calling tool").build();

        new DefaultConversationManager(ContextAttachmentProvider.NONE).addMessage(LoopMessages.builder()
                .execution(execution).response(ChatResponseEntity.builder().aiMessageEntity(ai).build())
                .toolExecuteResults(List.of(result)).build());

        assertEquals(2, execution.getMessages().size());
        assertSame(ai, execution.getMessages().getFirst());
        ToolMessageEntity tool = assertInstanceOf(ToolMessageEntity.class, execution.getMessages().get(1));
        assertEquals("call-1", tool.getId());
        assertEquals("lookup", tool.getName());
        assertEquals("tool result", tool.getToolOutput());
        assertEquals("tool result", tool.text());
        assertEquals(error, tool.isError());
    }
}
