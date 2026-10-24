package com.summit.runtime.compact;

import com.summit.core.agent.AgentRequest;
import com.summit.core.agent.CompactionState;
import com.summit.core.agent.Execution;
import com.summit.core.compact.ContextAttachmentProvider;
import com.summit.core.conversation.api.ChatRequestEntity;
import com.summit.core.conversation.api.ChatResponseEntity;
import com.summit.core.conversation.event.RuntimeEventPublisher;
import com.summit.core.conversation.message.AiMessageEntity;
import com.summit.core.conversation.message.Message;
import com.summit.core.conversation.message.SystemMessageEntity;
import com.summit.core.conversation.message.UserMessageEntity;
import com.summit.core.conversation.message.ToolMessageEntity;
import com.summit.core.model.chat.ChatModel;
import com.summit.core.runtime.loop.ContextUsageReporter;
import com.summit.runtime.agent.AgentConfig;
import com.summit.runtime.conversation.DefaultConversationManager;
import com.summit.runtime.loop.BoundaryChecker;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * State machine of the summarize band (design §3.1, §3.3.4, §3.3.5) — cases 1 to 9 of §8.2.
 *
 * <p>These tests pin the two P0 regressions: the attempt counter is incremented <b>before</b> the
 * compact model is called (so a throwing call is counted), and a context rebuild never resets the
 * attempt counter nor clears the "summarization is closed" latch.</p>
 */
class CompactionStateMachineTest {

    /** Ratio above the model threshold: the summarize band is entered. */
    private static final double OVER_THRESHOLD = 0.95;
    /** Ratio in the safe zone: no band is entered. */
    private static final double SAFE_RATIO = 0.10;
    /** Model threshold used by the fixture policy. */
    private static final double MODEL_THRESHOLD = 0.85;
    /** A compact-model answer the summary resolver accepts. */
    private static final String SUMMARY_JSON = "{\"summary\":\"SUMMARY-ALPHA\",\"goal\":\"goal\"}";
    /** Marks the pre-compaction history so a test can prove it is no longer sent. */
    private static final String HISTORY_MARKER = "ORIGINAL-HISTORY-MARKER";

    private final List<ChatRequestEntity> requests = new ArrayList<>();
    private StubContextTokenizer tokenizer = new StubContextTokenizer(OVER_THRESHOLD);
    private DefaultConversationManager conversations =
            new DefaultConversationManager(ContextAttachmentProvider.NONE);

    // ---------------------------------------------------------------- Summary cases 1-9

    /** Case 1: one failure closes summarization, so the failing model is called exactly once. */
    @Test
    void summaryFailureDegradesOnceAndNeverRetriesWithinExecution() {
        Execution execution = execution();
        BoundaryChecker checker = checker(failingModel(), 2);

        for (int round = 0; round < 5; round++) {
            checker.before(execution);
            checker.after(execution);
        }

        assertEquals(1, requests.size(),
                "a failing compact model must be called once per execution, not once per checkpoint");
        CompactionState state = execution.getCompactionState();
        assertTrue(state.summaryUnavailable(), "a failed summarize closes summarization for the execution");
        assertEquals(1, state.summaryAttempts());
    }

    /** Case 2: a rerun re-summarizes the whole live context (previous summary + retained), not the compressed history. */
    @Test
    void summaryInputSummarizesTheWholeLiveContext() {
        Execution execution = execution();
        BoundaryChecker checker = checker(answeringModel(), 3);

        checker.before(execution);
        checker.before(execution);

        assertEquals(2, requests.size());
        String firstInput = payloadOf(requests.get(0));
        String secondInput = payloadOf(requests.get(1));
        assertTrue(firstInput.contains(HISTORY_MARKER),
                "the first round summarizes the rendered history: " + firstInput);
        assertTrue(secondInput.contains("SUMMARY-ALPHA"),
                "the previous summary is part of the live context and is re-summarized: " + secondInput);
        assertFalse(secondInput.contains("first answer"),
                "the compressed-away assistant turn is gone, not replayed: " + secondInput);
        assertTrue(secondInput.contains(HISTORY_MARKER),
                "every retained user turn stays in the live context and is re-summarized: " + secondInput);
    }

    /** Case 2b (regression): a chained summary folds in work produced after the previous rebuild. */
    @Test
    void summaryFoldsInMessagesAddedAfterRebuild() {
        Execution execution = execution();
        BoundaryChecker checker = checker(answeringModel(), 4);

        checker.before(execution); // first generation: summarize the history and rebuild

        // Work done after the rebuild: it must reach the next summary instead of being dropped.
        execution.getMessages().add(AiMessageEntity.builder().text("NEW-ASSISTANT-WORK").build());
        execution.getMessages().add(ToolMessageEntity.builder()
                .id("new-tool-call").name("manage_command").toolOutput("NEW-TOOL-RESULT-MARKER")
                .isError(false).build());

        checker.before(execution); // second generation must fold the appended work in

        assertEquals(2, requests.size());
        String secondInput = payloadOf(requests.get(1));
        assertTrue(secondInput.contains("SUMMARY-ALPHA"),
                "the chained round still carries the previous summary: " + secondInput);
        assertTrue(secondInput.contains("NEW-TOOL-RESULT-MARKER"),
                "a chained summary must fold in tool results produced after the rebuild: " + secondInput);
    }

    /** Case 3: at the attempt budget the last result is accepted and summarization is closed. */
    @Test
    void chainedSummaryStopsAtMaxAttemptsAndAcceptsOversizedResult() {
        Execution execution = execution();
        BoundaryChecker checker = checker(answeringModel(), 2);

        for (int round = 0; round < 4; round++) {
            checker.before(execution);
        }

        assertEquals(2, requests.size(), "the attempt budget caps how often the model is called");
        CompactionState state = execution.getCompactionState();
        assertEquals(2, state.summaryAttempts());
        assertEquals(2, state.summaryGeneration(), "both successful rebuilds are recorded");
        assertTrue(state.summaryUnavailable(),
                "spending the budget while still over the threshold accepts the result and closes summarization");
    }

    /** Case 4: an unconfigured budget means a single attempt. */
    @Test
    void summaryAttemptsDefaultsToSingleWhenUnconfigured() {
        Execution execution = execution();
        BoundaryChecker checker = checker(answeringModel(), null);

        for (int round = 0; round < 3; round++) {
            checker.before(execution);
        }

        assertEquals(1, requests.size(), "without a configured budget only one attempt is allowed");
        assertTrue(execution.getCompactionState().summaryUnavailable());
    }

    /** Case 5: a rebuild that lands in the safe zone opens a new trigger window. */
    @Test
    void summaryAttemptsResetsPerTriggerWindow() {
        tokenizer = new StubContextTokenizer(OVER_THRESHOLD, SAFE_RATIO);
        Execution execution = execution();
        BoundaryChecker checker = checker(answeringModel(), 2);

        checker.before(execution);

        CompactionState afterSafeRebuild = execution.getCompactionState();
        assertEquals(1, afterSafeRebuild.summaryGeneration());
        assertEquals(0, afterSafeRebuild.summaryAttempts(),
                "observing the safe zone resets the attempt counter of the window");
        assertFalse(afterSafeRebuild.summaryUnavailable());

        tokenizer.ratio(OVER_THRESHOLD);
        checker.before(execution);

        assertEquals(2, requests.size(), "a new window may summarize again");
        assertEquals(2, execution.getCompactionState().summaryGeneration());
    }

    /** Case 6: the unavailable latch is execution-level and no rebuild may clear it. */
    @Test
    void summaryUnavailableStaysTrueForWholeExecution() {
        Execution execution = execution();
        BoundaryChecker checker = checker(failingModel(), 2);
        checker.before(execution);
        assertTrue(execution.getCompactionState().summaryUnavailable());

        conversations.rebuildContext("a summary produced by another path", execution);

        assertTrue(execution.getCompactionState().summaryUnavailable(),
                "a rebuild must never clear the unavailable latch");
        assertEquals(1, execution.getCompactionState().summaryAttempts());

        tokenizer.ratio(SAFE_RATIO);
        checker.before(execution);
        assertTrue(execution.getCompactionState().summaryUnavailable());
        assertEquals(1, requests.size(), "no further model call happens once summarization is closed");
    }

    /** Case 7: a rebuild advances the summary generation and invalidates the current prune. */
    @Test
    void summaryRebuildResetsPruneGeneration() {
        conversations = new DefaultConversationManager(ContextAttachmentProvider.NONE);
        Execution execution = execution();
        execution.setCompactionState(
                CompactionState.none().withSummaryGeneration(2).withPruneGeneration(1));

        conversations.rebuildContext("rebuilt summary", execution);

        CompactionState state = execution.getCompactionState();
        assertEquals(0, state.pruneGeneration(), "a new generation starts un-pruned");
        assertEquals(3, state.summaryGeneration());
    }

    /** Case 8 (P0-1 regression): two rebuilds later the attempt counter and the latch are intact. */
    @Test
    void rebuildDoesNotResetAttemptsOrUnavailable() {
        conversations = new DefaultConversationManager(ContextAttachmentProvider.NONE);
        Execution execution = execution();
        execution.setCompactionState(new CompactionState(1, 1, true, 1));

        conversations.rebuildContext("first rebuilt summary", execution);
        conversations.rebuildContext("second rebuilt summary", execution);

        CompactionState state = execution.getCompactionState();
        assertEquals(1, state.summaryAttempts(), "a rebuild must never reset the attempt counter");
        assertTrue(state.summaryUnavailable(), "a rebuild must never clear the unavailable latch");
        assertEquals(3, state.summaryGeneration());
        assertEquals(0, state.pruneGeneration());
    }

    /** Case 9 (P0-1 regression): the attempt is counted before the model call, exception included. */
    @Test
    void attemptsIncrementsBeforeModelCallNotAfter() {
        Execution execution = execution();
        List<Integer> attemptsSeenByModel = new ArrayList<>();
        ChatModel model = request -> {
            attemptsSeenByModel.add(execution.getCompactionState().summaryAttempts());
            throw new IllegalStateException("compact model unavailable");
        };
        BoundaryChecker checker = checker(model, 2);

        checker.before(execution);

        assertEquals(List.of(1), attemptsSeenByModel,
                "the attempt must already be counted when the compact model is invoked");
        assertEquals(1, execution.getCompactionState().summaryAttempts(),
                "an exception path must not lose the attempt");
        assertTrue(execution.getCompactionState().summaryUnavailable());
    }

    // ------------------------------------------------------------------ fixtures

    /** A checker wired exactly like the runtime does, with the scripted tokenizer. */
    private BoundaryChecker checker(ChatModel model, Integer maxSummaryAttempts) {
        conversations = new DefaultConversationManager(ContextAttachmentProvider.NONE);
        ContextUsageReporter usage = new ContextUsageReporter(tokenizer, 100_000,
                new RuntimeEventPublisher(List.of()), 1);
        DefaultModelCompacter modelCompacter = new DefaultModelCompacter(model, conversations,
                id -> Optional.empty(), usage);
        AgentConfig agentConfig = AgentConfig.builder()
                .maxTokens(100_000)
                .maxSummaryAttempts(maxSummaryAttempts)
                .squeezeThreshold(new AgentConfig.ProgressiveSqueezePolicy(
                        new AgentConfig.ModelSqueeze(MODEL_THRESHOLD)))
                .build();
        // The prune band backs the summarize band up: once summarization is closed the checkpoint
        // prunes, which these cases exercise indirectly through the execution state.
        DefaultContextPruner contextPruner = new DefaultContextPruner(agentConfig, usage);
        return new BoundaryChecker(agentConfig, tokenizer, conversations, contextPruner, modelCompacter);
    }

    /** Compact model that always answers with an acceptable summary and records every request. */
    private ChatModel answeringModel() {
        return request -> {
            requests.add(request);
            return ChatResponseEntity.builder()
                    .aiMessageEntity(AiMessageEntity.builder().text(SUMMARY_JSON).build())
                    .build();
        };
    }

    /** Compact model that is unreachable: every call throws. */
    private ChatModel failingModel() {
        return request -> {
            requests.add(request);
            throw new IllegalStateException("compact model unavailable");
        };
    }

    /** A session with a leading prompt, two user turns and a marker on the second one. */
    private static Execution execution() {
        List<Message> messages = new ArrayList<>(List.of(
                SystemMessageEntity.builder().text("LEADING-SYSTEM-PROMPT").build(),
                UserMessageEntity.from("first user request"),
                AiMessageEntity.builder().text("first answer").build(),
                UserMessageEntity.from(HISTORY_MARKER + " second user request")));
        return Execution.builder().id("execution-1")
                .agentRequest(AgentRequest.builder().messages(messages)
                        .systemPrompt("LEADING-SYSTEM-PROMPT").build())
                .messages(messages).build();
    }

    /** The payload the compact model was handed (the user message of the generated request). */
    private static String payloadOf(ChatRequestEntity request) {
        return request.getMessages().getLast().text();
    }
}
