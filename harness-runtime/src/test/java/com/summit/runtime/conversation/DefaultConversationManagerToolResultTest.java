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
import org.junit.jupiter.api.Test;
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

    /**
     * Case 33: the tool's own exit status travels from the result to the provider-facing message,
     * and stays independent of the framework's {@code code}.
     */
    @Test
    void toolMessageExitCodeSurvivesFromResultToProviderMessage() {
        ToolExecuteResult succeededWithNonZeroExit = ToolExecuteResult.success("command failed");
        succeededWithNonZeroExit.setId("call-1");
        succeededWithNonZeroExit.setExitCode(1);
        succeededWithNonZeroExit.setToolSpecification(ToolDefinition.builder().id("shell").name("shell")
                .timeout(5L).maxOutput(100).executor(call -> ToolExecuteResult.success("unused")).build());
        ToolExecuteResult withoutExitStatus = ToolExecuteResult.success("no status");
        withoutExitStatus.setId("call-2");
        withoutExitStatus.setToolSpecification(ToolDefinition.builder().id("lookup").name("lookup")
                .timeout(5L).maxOutput(100).executor(call -> ToolExecuteResult.success("unused")).build());

        Execution execution = Execution.builder().id("execution-1").agentRequest(AgentRequest.builder().build())
                .messages(new ArrayList<>()).build();
        new DefaultConversationManager(ContextAttachmentProvider.NONE).addMessage(LoopMessages.builder()
                .execution(execution)
                .response(ChatResponseEntity.builder()
                        .aiMessageEntity(AiMessageEntity.builder().text("calling tools").build()).build())
                .toolExecuteResults(List.of(succeededWithNonZeroExit, withoutExitStatus)).build());

        ToolMessageEntity failedCommand = assertInstanceOf(ToolMessageEntity.class, execution.getMessages().get(1));
        assertEquals(1, failedCommand.getExitCode(), "the tool's exit status reaches its message");
        assertFalse(failedCommand.isError(), "the framework still executed the tool successfully");
        assertNull(assertInstanceOf(ToolMessageEntity.class, execution.getMessages().get(2)).getExitCode(),
                "a tool without an exit status leaves the field empty instead of inventing zero");

        assertTrue(succeededWithNonZeroExit.isSuccess(),
                "a non-zero exit status must not be folded into the framework's own code");
        assertEquals(1, succeededWithNonZeroExit.getCode());
    }
}
