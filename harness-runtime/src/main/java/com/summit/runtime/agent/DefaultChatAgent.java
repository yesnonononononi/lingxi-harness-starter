package com.summit.runtime.agent;

import lombok.experimental.SuperBuilder;

/** Default ready-to-use chat agent supplied by LingXi. */
@SuperBuilder
public final class DefaultChatAgent extends ChatAgent {

    @Override
    public String id() {
        return "chat-agent";
    }
}
