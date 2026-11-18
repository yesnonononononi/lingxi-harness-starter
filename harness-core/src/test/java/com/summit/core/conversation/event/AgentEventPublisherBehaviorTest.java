package com.summit.core.conversation.event;

import com.summit.core.conversation.api.ChatResponseEntity;
import com.summit.core.conversation.message.AiMessageEntity;
import com.summit.core.runtime.AgentStreamListener;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The streaming surface, and why it is not the lifecycle surface.
 *
 * <p>This publisher fires once per token chunk, so its listeners are renderers. Keeping it separate
 * is what stops every auditor from being woken at token frequency — and a separate publisher is a
 * separate chance to lose the ordering or containment contract, which is pinned here.</p>
 */
class AgentEventPublisherBehaviorTest {

    @Test
    void everyStreamingHookReachesTheStreamListeners() {
        List<String> delivered = new ArrayList<>();
        AgentEventPublisher publisher = new AgentEventPublisher(List.of(recorder(0, delivered)));

        publisher.onPartialText(partialText());
        publisher.onPartialThinking(AgentPartialThinkingEvent.builder()
                .executionId("execution").responseId("response").content("think").offset(0).build());
        publisher.onCompleteText(AgentCompleteTextEvent.builder()
                .executionId("execution").responseId("response").content("done").build());
        publisher.onAiMessage(aiMessage());

        assertEquals(List.of("partial text", "partial thinking", "complete text", "ai message"), delivered);
    }

    @Test
    void listenersRunInAscendingOrderWhateverTheRegistrationOrder() {
        List<String> delivered = new ArrayList<>();
        AgentEventPublisher publisher = new AgentEventPublisher(List.of(
                ordered(300, "late", delivered),
                ordered(-100, "early", delivered),
                ordered(0, "middle", delivered)));

        publisher.onPartialText(partialText());

        assertEquals(List.of("early", "middle", "late"), delivered);
    }

    @Test
    void aBrokenRendererDoesNotStopTheOthers() {
        List<String> delivered = new ArrayList<>();
        AgentEventPublisher publisher = new AgentEventPublisher(List.of(
                new AgentStreamListener() {
                    @Override
                    public int order() {
                        return 0;
                    }

                    @Override
                    public void onPartialText(AgentPartialTextEvent event) {
                        throw new IllegalStateException("renderer failed");
                    }
                },
                ordered(1, "second", delivered)));

        publisher.onPartialText(partialText());

        assertEquals(List.of("second"), delivered);
    }

    /** A renderer holding part of the response contract can still choose to stop the dispatch. */
    @Test
    void aPropagatingRendererStopsTheRemainingOnes() {
        List<String> delivered = new ArrayList<>();
        IllegalStateException failure = new IllegalStateException("invariant failed");
        AgentEventPublisher publisher = new AgentEventPublisher(List.of(
                new AgentStreamListener() {
                    @Override
                    public int order() {
                        return 0;
                    }

                    @Override
                    public Behavior onPartialTextPolicy() {
                        return Behavior.PROPAGATE;
                    }

                    @Override
                    public void onPartialText(AgentPartialTextEvent event) {
                        throw failure;
                    }
                },
                ordered(1, "second", delivered)));

        assertThrows(IllegalStateException.class, () -> publisher.onPartialText(partialText()));
        assertEquals(List.of(), delivered);
    }

    @Test
    void theStreamingEventStaysARecordBecauseOneInstanceIsAllocatedPerChunk() {
        AgentPartialTextEvent event = partialText();

        assertEquals("PARTIAL_TEXT", event.type());
        assertEquals("delta", event.content());
        assertEquals(0, event.offset());
        assertEquals("execution", event.executionId());
        assertEquals(0, event.eventMetaData().size());
    }

    private static AgentPartialTextEvent partialText() {
        return AgentPartialTextEvent.builder()
                .executionId("execution").agentId("agent").responseId("response")
                .content("delta").offset(0).build();
    }

    private static AgentMessageEvent aiMessage() {
        return new AgentMessageEvent(
                ChatResponseEntity.builder()
                        .aiMessageEntity(AiMessageEntity.builder().text("answer").build())
                        .build(),
                "execution", "response", Map.of());
    }

    private static AgentStreamListener recorder(int order, List<String> delivered) {
        return new AgentStreamListener() {
            @Override
            public int order() {
                return order;
            }

            @Override
            public void onPartialText(AgentPartialTextEvent event) {
                delivered.add("partial text");
            }

            @Override
            public void onPartialThinking(AgentPartialThinkingEvent event) {
                delivered.add("partial thinking");
            }

            @Override
            public void onCompleteText(AgentCompleteTextEvent event) {
                delivered.add("complete text");
            }

            @Override
            public void onAiMessage(AgentMessageEvent event) {
                delivered.add("ai message");
            }
        };
    }

    private static AgentStreamListener ordered(int order, String name, List<String> delivered) {
        return new AgentStreamListener() {
            @Override
            public int order() {
                return order;
            }

            @Override
            public void onPartialText(AgentPartialTextEvent event) {
                delivered.add(name);
            }
        };
    }
}
