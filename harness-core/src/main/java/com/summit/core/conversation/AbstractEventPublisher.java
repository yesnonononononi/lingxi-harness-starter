package com.summit.core.conversation;

import com.summit.core.runtime.RuntimeListener;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;


@Slf4j
public abstract class AbstractEventPublisher<T extends EventListener> {
    @Getter
    private final List<T> listeners;

    public AbstractEventPublisher(List<T> listeners){
        this.listeners = listeners == null ? List.of() : listeners.stream()
                .sorted(Comparator.comparingInt(T::order))
                .toList();
    }


    /**
     * The single dispatch for every event, in ascending {@link RuntimeListener#order()}.
     *
     * <p>The policy is read per listener and per event: a listener may be an invariant carrier for
     * one hook and a plain observer for the next. Containment is the default, because one broken
     * observer must not take the run down with it. A propagating listener stops the dispatch, so the
     * listeners after it never see the event — the same contract the loop's interceptor chain
     * offers.</p>
     */
    public void publish(String event, Function<T, T.Behavior> policy,
                         Consumer<T> delivery) {
        for (T listener : listeners) {
            try {
                delivery.accept(listener);
            } catch (RuntimeException e) {
                RuntimeListener.Behavior behavior = policy.apply(listener);
                if (behavior == null || behavior.catchErr()) {
                    log.error("Error occurred while publishing {} event, listenerId:{}, order:{}",
                            event, listener.id(), listener.order(), e);
                } else {
                    throw e;
                }
            }
        }
    }
}
