package com.summit.runtime.compact;

import com.summit.core.agent.Execution;
import com.summit.core.compact.*;
import com.summit.core.conversation.api.*;
import com.summit.core.conversation.event.RuntimeEventPublisher;
import com.summit.core.conversation.message.*;
import com.summit.core.runtime.loop.ContextUsageReporter;
import com.summit.core.tool.ToolExecution;
import com.summit.runtime.conversation.*;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class CompactionRegressionTest {
    private final DefaultTokenizer tokenizer = new DefaultTokenizer();
    private final ContextUsageReporter usage = new ContextUsageReporter(tokenizer, 100_000,
            new RuntimeEventPublisher(List.of()), 1);

    @Test
    void automaticAndToolCompactionUseTheSamePromptAndProtectedAttachment() {
        List<ChatRequestEntity> requests = new ArrayList<>();
        var conversations = new DefaultConversationManager(ContextAttachmentProvider.NONE);
        var compacter = new DefaultModelCompacter(request -> {
            requests.add(request);
            return ChatResponseEntity.builder().aiMessageEntity(AiMessageEntity.builder()
                    .text("{\"summary\":\"summary\"}").build()).build();
        }, conversations, id -> Optional.of("protected state"), usage);
        var execution = Execution.builder().id("e").messages(new ArrayList<>(List.of(
                SystemMessageEntity.builder().text("system").build(), UserMessageEntity.from("history")))).build();
        assertTrue(compacter.compact(new ContextCompactRequest(execution, null)));
        assertEquals(com.summit.core.tool.ToolResultType.CONTEXT_COMPACT,
                compacter.execute(ToolExecution.builder().executionId("e").args("{\"context\":\"history\"}").build()).getToolResultType());
        assertEquals(2, requests.size());
        assertEquals(requests.getFirst().getMessages().getFirst().text(), requests.getLast().getMessages().getFirst().text());
        for (var request : requests) assertTrue(request.getMessages().getLast().text().contains("protected state"));
    }

    @Test
    void localCompactionMovesPastAnAlreadyTruncatedRound() {
        var messages = new ArrayList<Message>();
        messages.add(SystemMessageEntity.builder().text("system").build());
        for (int i = 0; i < 2; i++) {
            messages.add(AiMessageEntity.builder().toolCalls(List.of(new ToolCallRequest("c" + i, "read", "{}", null))).build());
            messages.add(ToolMessageEntity.builder().id("c" + i).name("read").text("x".repeat(2_000)).build());
        }
        var execution = Execution.builder().id("e").messages(messages).build();
        var compacter = new DefaultManualCompacter(ContextAttachmentProvider.NONE, tokenizer, usage);
        assertTrue(compacter.compact(new ContextCompactRequest(execution,
                ContextSqueezeRequest.builder().shouldSqueeze(true).truncateTurn(2).build())));
        assertTrue(messages.get(2).text().length() < 500);
        assertTrue(messages.get(4).text().length() < 500);
        assertFalse(compacter.compact(new ContextCompactRequest(execution, null)));
    }
}
