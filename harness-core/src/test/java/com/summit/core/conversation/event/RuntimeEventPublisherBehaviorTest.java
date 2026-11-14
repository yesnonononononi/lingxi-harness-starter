package com.summit.core.conversation.event;

import com.summit.core.runtime.RuntimeListener;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * How one listener's failure affects the remaining listeners, and in what order they run.
 *
 * <p>Containment is the default: an observer that breaks must not take the run down with it. A
 * listener that carries part of the run's own invariant answers {@code PROPAGATE} for that one hook,
 * and its failure then stops the dispatch.</p>
 */
class RuntimeEventPublisherBehaviorTest {

    @Test
    void aContainedFailureDoesNotStopTheOtherListeners() {
        List<String> delivered = new ArrayList<>();
        RuntimeEventPublisher publisher = new RuntimeEventPublisher(List.of(
                startListener(0, delivered, "first"),
                failingStartListener(1, "broken", new IllegalStateException("observer failed"), null),
                startListener(2, delivered, "third")));

        publisher.onExecutionStart(new ExecutionStartEvent("execution"));

        assertEquals(List.of("first", "third"), delivered);
    }

    @Test
    void aPropagatingPolicyStopsTheRemainingListeners() {
        List<String> delivered = new ArrayList<>();
        IllegalStateException failure = new IllegalStateException("invariant failed");
        RuntimeEventPublisher publisher = new RuntimeEventPublisher(List.of(
                startListener(0, delivered, "first"),
                failingStartListener(1, "broken", failure, RuntimeListener.Behavior.PROPAGATE),
                startListener(2, delivered, "third")));

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> publisher.onExecutionStart(new ExecutionStartEvent("execution")));

        assertSame(failure, thrown, "The listener's own failure must reach the caller unchanged");
        assertEquals(List.of("first"), delivered, "A propagating listener stops the dispatch");
    }

    @Test
    void aListenerDeclaringNoPolicyIsContained() {
        List<String> delivered = new ArrayList<>();
        RuntimeEventPublisher publisher = new RuntimeEventPublisher(List.of(
                failingStartListener(0, "broken", new IllegalStateException("observer failed"), null),
                startListener(1, delivered, "second")));

        publisher.onExecutionStart(new ExecutionStartEvent("execution"));

        assertEquals(List.of("second"), delivered);
    }

    @Test
    void aListenerReturningNullPolicyIsContained() {
        List<String> delivered = new ArrayList<>();
        RuntimeEventPublisher publisher = new RuntimeEventPublisher(List.of(
                new RuntimeListener() {
                    @Override
                    public int order() {
                        return 0;
                    }

                    @Override
                    public Behavior onExecutionStartPolicy() {
                        return null;
                    }

                    @Override
                    public void onExecutionStart(ExecutionStartEvent event) {
                        throw new IllegalStateException("null policy observer failed");
                    }
                },
                startListener(1, delivered, "second")));

        publisher.onExecutionStart(new ExecutionStartEvent("execution"));

        assertEquals(List.of("second"), delivered);
    }

    @Test
    void listenersRunInAscendingOrderWhateverTheRegistrationOrder() {
        List<String> delivered = new ArrayList<>();
        RuntimeEventPublisher publisher = new RuntimeEventPublisher(List.of(
                startListener(300, delivered, "late"),
                startListener(-100, delivered, "early"),
                startListener(0, delivered, "middle")));

        publisher.onExecutionStart(new ExecutionStartEvent("execution"));

        assertEquals(List.of("early", "middle", "late"), delivered);
    }

    /**
     * The policy belongs to the hook, not to the listener: the same listener can be an invariant
     * carrier for one event and an ordinary observer for the next.
     */
    @Test
    void thePolicyIsReadPerEvent() {
        List<String> delivered = new ArrayList<>();
        IllegalStateException failure = new IllegalStateException("start is an invariant");
        RuntimeEventPublisher publisher = new RuntimeEventPublisher(List.of(new RuntimeListener() {
            @Override
            public int order() {
                return 0;
            }

            @Override
            public Behavior onExecutionStartPolicy() {
                return Behavior.PROPAGATE;
            }

            @Override
            public void onExecutionStart(ExecutionStartEvent event) {
                throw failure;
            }

            @Override
            public void onExecutionResumed(ExecutionResumedEvent event) {
                throw failure;
            }
        }, new RuntimeListener() {
            @Override
            public int order() {
                return 1;
            }

            @Override
            public void onExecutionResumed(ExecutionResumedEvent event) {
                delivered.add("resumed");
            }
        }));

        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> publisher.onExecutionStart(new ExecutionStartEvent("execution"))));

        publisher.onExecutionResumed(new ExecutionResumedEvent("execution"));
        assertEquals(List.of("resumed"), delivered, "The default policy keeps the next hook contained");
    }

    private RuntimeListener startListener(int order, List<String> delivered, String name) {
        return new RuntimeListener() {
            @Override
            public int order() {
                return order;
            }

            @Override
            public void onExecutionStart(ExecutionStartEvent event) {
                delivered.add(name);
            }
        };
    }

    /** A {@code null} behavior means "declare no policy", which must fall back to containment. */
    private RuntimeListener failingStartListener(int order, String name, RuntimeException failure,
                                                  RuntimeListener.Behavior behavior) {
        return new RuntimeListener() {
            @Override
            public int order() {
                return order;
            }

            @Override
            public Behavior onExecutionStartPolicy() {
                return behavior == null ? RuntimeListener.super.onExecutionStartPolicy() : behavior;
            }

            @Override
            public String id() {
                return name;
            }

            @Override
            public void onExecutionStart(ExecutionStartEvent event) {
                throw failure;
            }
        };
    }
}
