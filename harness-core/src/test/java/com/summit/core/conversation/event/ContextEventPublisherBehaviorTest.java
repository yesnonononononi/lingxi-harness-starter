package com.summit.core.conversation.event;

import com.summit.core.runtime.RuntimeListener;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The context channel is its own surface.
 *
 * <p>The split only pays off if a context fact cannot reach a listener that watches the run's
 * lifecycle: that separation is what lets a renderer subscribe to tokens without being woken for a
 * memory-section change, and lets an auditor subscribe to a run without being woken per token.
 * Ordering and failure containment are the same contract as every other publisher and are pinned
 * here too, because a separate publisher is a separate chance to get them wrong.</p>
 */
class ContextEventPublisherBehaviorTest {

    @Test
    void everyContextHookReachesTheContextListeners() {
        List<String> delivered = new ArrayList<>();
        ContextEventPublisher publisher = new ContextEventPublisher(List.of(recorder(0, delivered)));

        publisher.onContextChanged(ContextChangedEvent.builder().executionId("execution").build());
        publisher.onContextUpdated(update());
        publisher.onContextRebuilt(ContextRebuiltEvent.builder().executionId("execution").build());

        assertEquals(List.of("changed", "updated", "rebuilt"), delivered);
    }

    @Test
    void aContextFactNeverReachesAListenerThatOnlyWatchesTheRun() {
        List<String> contextTrail = new ArrayList<>();
        List<String> lifecycleTrail = new ArrayList<>();
        ContextEventPublisher contextEvents = new ContextEventPublisher(List.of(recorder(0, contextTrail)));
        RuntimeEventPublisher runtimeEvents = new RuntimeEventPublisher(List.of(new RuntimeListener() {
            @Override
            public int order() {
                return 0;
            }

            @Override
            public void onExecutionStart(ExecutionStartEvent event) {
                lifecycleTrail.add("start");
            }
        }));

        contextEvents.onContextChanged(ContextChangedEvent.builder().executionId("execution").build());
        runtimeEvents.onExecutionStart(new ExecutionStartEvent("execution"));

        assertEquals(List.of("changed"), contextTrail);
        assertEquals(List.of("start"), lifecycleTrail, "the lifecycle channel never sees a context event");
    }

    @Test
    void aBrokenContextListenerDoesNotStopTheOthers() {
        List<String> delivered = new ArrayList<>();
        ContextEventPublisher publisher = new ContextEventPublisher(List.of(
                new ContextListener() {
                    @Override
                    public int order() {
                        return 0;
                    }

                    @Override
                    public void onContextChanged(ContextChangedEvent event) {
                        throw new IllegalStateException("observer failed");
                    }
                },
                recorder(1, delivered)));

        publisher.onContextChanged(ContextChangedEvent.builder().executionId("execution").build());

        assertEquals(List.of("changed"), delivered);
    }

    @Test
    void listenersRunInAscendingOrderWhateverTheRegistrationOrder() {
        List<String> delivered = new ArrayList<>();
        ContextEventPublisher publisher = new ContextEventPublisher(List.of(
                ordered(300, "late", delivered),
                ordered(-100, "early", delivered),
                ordered(0, "middle", delivered)));

        publisher.onContextChanged(ContextChangedEvent.builder().executionId("execution").build());

        assertEquals(List.of("early", "middle", "late"), delivered);
    }

    @Test
    void aRebuildHasItsOwnDiscriminatorInsteadOfBeingOneMoreChangeReason() {
        assertEquals("CONTEXT_REBUILT", ContextRebuiltEvent.builder()
                .executionId("execution").summaryGeneration(2).messageCount(7).build().type());
        assertEquals("CONTEXT_CHANGED", ContextChangedEvent.builder()
                .executionId("execution").build().type());
    }

    private static ContextUpdateEvent update() {
        return new ContextUpdateEvent("execution", ContextUpdateEvent.Phase.UPDATE, null, null);
    }

    private static ContextListener recorder(int order, List<String> delivered) {
        return new ContextListener() {
            @Override
            public int order() {
                return order;
            }

            @Override
            public void onContextChanged(ContextChangedEvent event) {
                delivered.add("changed");
            }

            @Override
            public void onContextUpdated(ContextUpdateEvent event) {
                delivered.add("updated");
            }

            @Override
            public void onContextRebuilt(ContextRebuiltEvent event) {
                delivered.add("rebuilt");
            }
        };
    }

    private static ContextListener ordered(int order, String name, List<String> delivered) {
        return new ContextListener() {
            @Override
            public int order() {
                return order;
            }

            @Override
            public void onContextChanged(ContextChangedEvent event) {
                delivered.add(name);
            }
        };
    }
}
