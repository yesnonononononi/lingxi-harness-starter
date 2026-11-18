package com.summit.core.conversation.event;

import com.summit.core.conversation.AbstractEventPublisher;
import com.summit.core.runtime.AgentStreamListener;

import java.util.List;

/**
 * The streaming surface, published to {@link AgentStreamListener}s.
 *
 * <p>Split from {@link RuntimeEventPublisher} because the two have opposite traffic profiles: this
 * one fires per token chunk and its listeners are renderers, while the lifecycle publisher fires a
 * handful of times per run and its listeners audit. Merging them forced every audit listener to be
 * woken up at token frequency.</p>
 */
public class AgentEventPublisher extends AbstractEventPublisher<AgentStreamListener> {

    public AgentEventPublisher(List<AgentStreamListener> listeners) {
        super(listeners);
    }

    public void onPartialText(AgentPartialTextEvent event) {
        publish("partial text", AgentStreamListener::onPartialTextPolicy,
                listener -> listener.onPartialText(event));
    }

    public void onPartialThinking(AgentPartialThinkingEvent event) {
        publish("partial thinking", AgentStreamListener::onPartialThinkingPolicy,
                listener -> listener.onPartialThinking(event));
    }

    public void onCompleteText(AgentCompleteTextEvent event) {
        publish("complete text", AgentStreamListener::onCompleteTextPolicy,
                listener -> listener.onCompleteText(event));
    }

    public void onAiMessage(AgentMessageEvent event) {
        publish("ai message", AgentStreamListener::onAiMessagePolicy,
                listener -> listener.onAiMessage(event));
    }
}
