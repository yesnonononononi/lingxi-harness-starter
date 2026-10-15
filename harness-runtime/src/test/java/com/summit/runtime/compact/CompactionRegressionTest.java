package com.summit.runtime.compact;

import com.summit.core.agent.AgentRequest;
import com.summit.core.agent.Execution;
import com.summit.core.compact.*;
import com.summit.core.conversation.api.*;
import com.summit.core.conversation.event.ContextUpdateEvent;
import com.summit.core.conversation.event.RuntimeEventPublisher;
import com.summit.core.runtime.RuntimeListener;
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
        var history = new ArrayList<Message>(List.of(
                SystemMessageEntity.builder().text("system").build(), UserMessageEntity.from("history")));
        var execution = Execution.builder().id("e").messages(history)
                .agentRequest(AgentRequest.builder().messages(history).build()).build();
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
            messages.add(AiMessageEntity.builder().toolCalls(List.of(new ToolCallRequest("c" + i, "read", 0, "{}"))).build());
            messages.add(ToolMessageEntity.builder().id("c" + i).name("read").text("x".repeat(2_000)).build());
        }
        var execution = Execution.builder().id("e").messages(messages)
                .agentRequest(AgentRequest.builder().messages(messages).build()).build();
        var compacter = new DefaultManualCompacter(ContextAttachmentProvider.NONE, tokenizer, usage);
        assertTrue(compacter.compact(new ContextCompactRequest(execution,
                ContextSqueezeRequest.builder().shouldSqueeze(true).truncateTurn(2).build())));
        assertTrue(messages.get(2).text().length() < 500);
        assertTrue(messages.get(4).text().length() < 500);
        assertFalse(compacter.compact(new ContextCompactRequest(execution, null)));
    }

    @Test
    void manualCompactionEmitsAPairedBandTaggedSqueeze() {
        List<ContextUpdateEvent> events = new ArrayList<>();
        var reporting = capturingReporter(events);
        var messages = new ArrayList<Message>();
        messages.add(SystemMessageEntity.builder().text("system").build());
        messages.add(AiMessageEntity.builder().toolCalls(List.of(new ToolCallRequest("c0", "read", 0, "{}"))).build());
        messages.add(ToolMessageEntity.builder().id("c0").name("read").text("x".repeat(2_000)).build());
        var execution = Execution.builder().id("e").messages(messages)
                .agentRequest(AgentRequest.builder().messages(messages).build()).build();
        var compacter = new DefaultManualCompacter(ContextAttachmentProvider.NONE, tokenizer, reporting);

        assertTrue(compacter.compact(new ContextCompactRequest(execution,
                ContextSqueezeRequest.builder().shouldSqueeze(true).truncateTurn(2).build())));
        assertEquals(2, events.size());
        assertEquals(ContextUpdateEvent.Phase.SQUEEZE_STARTED, events.getFirst().getPhase());
        assertEquals(ContextUpdateEvent.Phase.SQUEEZE_COMPLETED, events.getLast().getPhase());
        events.forEach(event -> assertEquals(ContextUpdateEvent.Band.MANUAL, event.getBand()));

        // A pass that squeezes nothing still closes the pair: a lone STARTED would leave consumers
        // stuck on the in-flight state until some unrelated event cleared it.
        events.clear();
        assertFalse(compacter.compact(new ContextCompactRequest(execution, null)));
        assertEquals(2, events.size());
        events.forEach(event -> assertEquals(ContextUpdateEvent.Band.MANUAL, event.getBand()));
    }

    @Test
    void modelCompactionClosesTheSqueezePairOnFailure() {
        List<ContextUpdateEvent> events = new ArrayList<>();
        var reporting = capturingReporter(events);
        var conversations = new DefaultConversationManager(ContextAttachmentProvider.NONE);
        var compacter = new DefaultModelCompacter(request -> {
            throw new IllegalStateException("model unavailable");
        }, conversations, id -> Optional.empty(), reporting);
        var history = new ArrayList<Message>(List.of(
                SystemMessageEntity.builder().text("system").build(), UserMessageEntity.from("history")));
        var execution = Execution.builder().id("e").messages(history)
                .agentRequest(AgentRequest.builder().messages(history).build()).build();

        assertFalse(compacter.compact(new ContextCompactRequest(execution, null)));
        assertEquals(2, events.size());
        assertEquals(ContextUpdateEvent.Phase.SQUEEZE_STARTED, events.getFirst().getPhase());
        assertEquals(ContextUpdateEvent.Phase.SQUEEZE_COMPLETED, events.getLast().getPhase());
        events.forEach(event -> assertEquals(ContextUpdateEvent.Band.SUMMARY, event.getBand()));
    }

    /** Reporter wired to a capturing listener: the tests assert on the emitted event sequence. */
    private ContextUsageReporter capturingReporter(List<ContextUpdateEvent> events) {
        return new ContextUsageReporter(tokenizer, 100_000, new RuntimeEventPublisher(List.of(new RuntimeListener() {
            @Override
            public void onContextUpdate(ContextUpdateEvent event) {
                events.add(event);
            }
        })), 1);
    }
}
