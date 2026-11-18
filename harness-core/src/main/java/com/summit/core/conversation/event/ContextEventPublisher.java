package com.summit.core.conversation.event;

import com.summit.core.conversation.AbstractEventPublisher;

import java.util.List;

/**
 * Everything that happens to an execution's context, published to {@link ContextListener}s.
 *
 * <p>Also the publisher of the compaction telemetry ({@link ContextUpdateEvent}) — it used to travel
 * through the lifecycle publisher, which meant a listener interested in context had to subscribe to
 * execution lifecycle as well.</p>
 */
public class ContextEventPublisher extends AbstractEventPublisher<ContextListener> {

    public ContextEventPublisher(List<ContextListener> listeners) {
        super(listeners);
    }

    public void onContextChanged(ContextChangedEvent event) {
        publish("context changed", ContextListener::onContextChangedPolicy,
                listener -> listener.onContextChanged(event));
    }

    public void onContextUpdated(ContextUpdateEvent event) {
        publish("context updated", ContextListener::onContextUpdatedPolicy,
                listener -> listener.onContextUpdated(event));
    }

    public void onContextRebuilt(ContextRebuiltEvent event) {
        publish("context rebuilt", ContextListener::onContextRebuiltPolicy,
                listener -> listener.onContextRebuilt(event));
    }
}
