package com.summit.core.runtime;

import com.summit.core.conversation.EventListener;
import com.summit.core.conversation.event.*;

/**
 * The streaming surface of a model response, for renderers.
 *
 * <p>Deliberately narrower than {@link RuntimeListener}: a listener that only draws text has no
 * reason to receive lifecycle, tool or context notifications, and a listener that audits a run has
 * no reason to be woken per token chunk. The {@link EventListener.Behavior} policy works exactly as
 * on the runtime side — a renderer that throws must not take the run down.</p>
 */
public interface AgentStreamListener extends EventListener {

    /** Failure policy of {@link #onPartialText}. */
    default Behavior onPartialTextPolicy() {
        return Behavior.CONTAIN;
    }

    /** Failure policy of {@link #onPartialThinking}. */
    default Behavior onPartialThinkingPolicy() {
        return Behavior.CONTAIN;
    }

    /** Failure policy of {@link #onCompleteText}. */
    default Behavior onCompleteTextPolicy() {
        return Behavior.CONTAIN;
    }

    /** Failure policy of {@link #onAiMessage}. */
    default Behavior onAiMessagePolicy() {
        return Behavior.CONTAIN;
    }

    default void onPartialText(AgentPartialTextEvent event) {
    }

    default void onPartialThinking(AgentPartialThinkingEvent event) {
    }

    default void onCompleteText(AgentCompleteTextEvent event) {
    }

    default void onAiMessage(AgentMessageEvent event) {
    }
}
