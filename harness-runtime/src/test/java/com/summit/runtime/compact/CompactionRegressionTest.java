package com.summit.runtime.compact;

import com.summit.core.agent.AgentRequest;
import com.summit.core.agent.Execution;
import com.summit.core.compact.ContextAttachmentProvider;
import com.summit.core.compact.ContextCompactRequest;
import com.summit.core.conversation.api.ChatRequestEntity;
import com.summit.core.conversation.api.ChatResponseEntity;
import com.summit.core.conversation.api.ToolCallRequest;
import com.summit.core.conversation.event.ContextUpdateEvent;
import com.summit.core.conversation.event.RuntimeEventPublisher;
import com.summit.core.conversation.message.*;
import com.summit.core.model.chat.ChatModel;
import com.summit.core.runtime.RuntimeListener;
import com.summit.core.runtime.loop.ContextUsageReporter;
import com.summit.core.tool.ToolExecution;
import com.summit.runtime.agent.AgentConfig;
import com.summit.runtime.conversation.DefaultConversationManager;
import com.summit.runtime.conversation.DefaultTokenizer;
import com.summit.runtime.loop.BoundaryChecker;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The regressions that must survive the compaction redesign (§8.1).
 *
 * <p>The four cases of the old suite that used the deleted truncation band are rewritten against
 * the prune band: pruning keeps the skeleton and drops payloads, always emits a paired
 * band-tagged squeeze event (also when it changes nothing), never rewrites system messages, and a
 * failing summary hands the checkpoint over to pruning instead of retrying the model.</p>
 */
class CompactionRegressionTest {
    private static final int MAX_TOKENS = 10_000;

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

        // Pruning runs on the rebuilt session and must never rewrite its system prefix.
        Execution rebuilt = executionWith(
                SystemMessageEntity.builder().text("system").build(),
                SystemMessageEntity.builder().text("summary").build(),
                UserMessageEntity.from("history"),
                AiMessageEntity.builder().toolCalls(List.of(new ToolCallRequest("c1", "read", 0, "{}"))).build(),
                ToolMessageEntity.builder().id("c1").name("read").toolOutput("x".repeat(3_000)).build());
        assertTrue(pruner(0.0001d).compact(new ContextCompactRequest(rebuilt, null)));
        assertEquals(List.of("system", "summary"), rebuilt.getMessages().stream()
                        .filter(SystemMessageEntity.class::isInstance).map(Message::text).toList(),
                "pruning keeps every system message, in order and untouched");
    }

    @Test
    void pruneKeepsSkeletonAndDropsToolOutputs() {
        Execution execution = executionWith(
                SystemMessageEntity.builder().text("s".repeat(300)).build(),
                UserMessageEntity.from("u".repeat(300)),
                AiMessageEntity.builder().text("old turn")
                        .toolCalls(List.of(new ToolCallRequest("c1", "read", 0, "{}"))).build(),
                ToolMessageEntity.builder().id("c1").name("read").toolOutput("x".repeat(9_000)).build());

        assertTrue(pruner(0.025d).compact(new ContextCompactRequest(execution, null)));

        assertEquals(4, execution.getMessages().size(), "the segment survives as a skeleton");
        AiMessageEntity ai = assertInstanceOf(AiMessageEntity.class, execution.getMessages().get(2));
        assertEquals("old turn", ai.text(), "the skeleton keeps the assistant text");
        assertEquals(1, ai.getToolCalls().size(), "the skeleton keeps the tool calls");
        ToolMessageEntity tool = assertInstanceOf(ToolMessageEntity.class, execution.getMessages().get(3));
        assertTrue(tool.text().startsWith("[output omitted ·"), "the payload is replaced: " + tool.text());
        assertTrue(tool.text().length() < 200, "the placeholder is one line, not the payload");
        assertEquals("c1", tool.getId(), "the pairing to the assistant tool call survives");
    }

    @Test
    void pruneEmitsAPairedBandTaggedSqueeze() {
        List<ContextUpdateEvent> events = new ArrayList<>();
        DefaultContextPruner pruner = new DefaultContextPruner(
                AgentConfig.builder().maxTokens(MAX_TOKENS).pruneTargetRatio(0.40d).build(),
                capturingReporter(events));

        Execution trimmable = executionWith(
                SystemMessageEntity.builder().text("system").build(),
                UserMessageEntity.from("question"),
                AiMessageEntity.builder().toolCalls(List.of(new ToolCallRequest("c0", "read", 0, "{}"))).build(),
                ToolMessageEntity.builder().id("c0").name("read").toolOutput("x".repeat(100_000)).build());

        assertTrue(pruner.compact(new ContextCompactRequest(trimmable, null)));
        assertEquals(2, events.size());
        assertEquals(ContextUpdateEvent.Phase.SQUEEZE_STARTED, events.getFirst().getPhase());
        assertEquals(ContextUpdateEvent.Phase.SQUEEZE_COMPLETED, events.getLast().getPhase());
        events.forEach(event -> assertEquals(ContextUpdateEvent.Band.MANUAL, event.getBand()));

        // A pass that trims nothing still closes the pair: a lone STARTED would leave consumers
        // stuck on the in-flight state until some unrelated event cleared it.
        events.clear();
        Execution alreadySmall = executionWith(
                SystemMessageEntity.builder().text("system").build(),
                UserMessageEntity.from("question"),
                AiMessageEntity.builder().toolCalls(List.of(new ToolCallRequest("c0", "read", 0, "{}"))).build(),
                ToolMessageEntity.builder().id("c0").name("read").toolOutput("tiny").build());
        assertFalse(pruner.compact(new ContextCompactRequest(alreadySmall, null)),
                "nothing to trim, yet the pass still reports itself");
        assertEquals(2, events.size());
        assertEquals(ContextUpdateEvent.Phase.SQUEEZE_STARTED, events.getFirst().getPhase());
        assertEquals(ContextUpdateEvent.Phase.SQUEEZE_COMPLETED, events.getLast().getPhase());
        events.forEach(event -> assertEquals(ContextUpdateEvent.Band.MANUAL, event.getBand()));
    }

    @Test
    void modelCompactionClosesTheSqueezePairOnFailureAndHandsOverToPrune() {
        List<ContextUpdateEvent> events = new ArrayList<>();
        List<Integer> modelCalls = new ArrayList<>();
        var conversations = new DefaultConversationManager(ContextAttachmentProvider.NONE);
        DefaultModelCompacter compacter = new DefaultModelCompacter(request -> {
            modelCalls.add(1);
            throw new IllegalStateException("model unavailable");
        }, conversations, id -> Optional.empty(), capturingReporter(events));

        Execution execution = executionWith(
                SystemMessageEntity.builder().text("system").build(),
                UserMessageEntity.from("history"),
                AiMessageEntity.builder().toolCalls(List.of(new ToolCallRequest("c0", "read", 0, "{}"))).build(),
                ToolMessageEntity.builder().id("c0").name("read").toolOutput("x".repeat(30_000)).build());

        assertFalse(compacter.compact(new ContextCompactRequest(execution, null)));
        assertEquals(2, events.size());
        assertEquals(ContextUpdateEvent.Phase.SQUEEZE_STARTED, events.getFirst().getPhase());
        assertEquals(ContextUpdateEvent.Phase.SQUEEZE_COMPLETED, events.getLast().getPhase());
        events.forEach(event -> assertEquals(ContextUpdateEvent.Band.SUMMARY, event.getBand()));

        // The next checkpoints must not call the failing model again: the summarize gate is closed,
        // so the checkpoint prunes instead.
        StubContextTokenizer ratioTokenizer = new StubContextTokenizer(0.95);
        AgentConfig config = AgentConfig.builder().maxTokens(MAX_TOKENS)
                .squeezeThreshold(new AgentConfig.ProgressiveSqueezePolicy(
                        new AgentConfig.OriginalSqueeze(0.7, 5), new AgentConfig.ModelSqueeze(0.85)))
                .build();
        BoundaryChecker checker = new BoundaryChecker(config, ratioTokenizer, conversations,
                new DefaultContextPruner(config, capturingReporter(events)), compacter);
        events.clear();

        checker.before(execution);
        int callsAfterFirstCheckpoint = modelCalls.size();
        assertEquals(2, callsAfterFirstCheckpoint,
                "the direct call plus one checkpoint attempt, and no more");
        checker.before(execution);
        checker.before(execution);
        checker.before(execution);

        assertEquals(callsAfterFirstCheckpoint, modelCalls.size(),
                "a failing summarize is never retried within the execution");
        assertTrue(execution.getCompactionState().summaryUnavailable());
        assertEquals(1, execution.getCompactionState().pruneGeneration(), "the prune band ran exactly once");
        assertTrue(events.stream().anyMatch(event -> event.getBand() == ContextUpdateEvent.Band.MANUAL),
                "the closed summarize band hands the checkpoint over to pruning");
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

    private DefaultContextPruner pruner(double pruneTargetRatio) {
        return new DefaultContextPruner(AgentConfig.builder()
                .maxTokens(MAX_TOKENS).pruneTargetRatio(pruneTargetRatio)
                .fullToolOutputCap(100).build(), usage);
    }

    private static Execution executionWith(Message... messages) {
        List<Message> list = new ArrayList<>(List.of(messages));
        return Execution.builder().id("e").messages(list)
                .agentRequest(AgentRequest.builder().messages(list).build()).build();
    }
}
