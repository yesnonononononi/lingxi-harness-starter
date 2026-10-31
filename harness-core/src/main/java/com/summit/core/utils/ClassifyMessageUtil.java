package com.summit.core.utils;

import com.summit.core.conversation.api.ToolCallRequest;
import com.summit.core.conversation.message.AiMessageEntity;
import com.summit.core.conversation.message.Message;
import com.summit.core.conversation.message.SystemMessageEntity;
import com.summit.core.conversation.message.ToolMessageEntity;
import com.summit.core.conversation.message.UserMessageEntity;
import lombok.Getter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/** Groups user turns and their tool calls without changing the input messages. */
public final class ClassifyMessageUtil {
    private ClassifyMessageUtil() {
    }

    public static final class AiSegment {
        @Getter
        private final AiMessageEntity aiMessageEntity;
        private final List<ToolMessageEntity> toolMessageEntities = new ArrayList<>();

        private AiSegment(AiMessageEntity aiMessageEntity) {
            this.aiMessageEntity = aiMessageEntity;
        }

        public List<ToolMessageEntity> getToolMessageEntities() {
            return List.copyOf(toolMessageEntities);
        }
    }

    public static final class Segment {
        @Getter
        private final UserMessageEntity userMessageEntity;
        private final List<AiSegment> aiSegments = new ArrayList<>();
        private final List<Message> messages = new ArrayList<>();
        @Getter
        private int tokenCounts;

        private Segment(UserMessageEntity userMessageEntity) {
            this.userMessageEntity = userMessageEntity;
            messages.add(userMessageEntity);
        }

        public List<AiSegment> getAiSegments() {
            return List.copyOf(aiSegments);
        }

        /** Retains original slots: grouping results next to their owner can reorder interleaved calls. */
        public List<Message> export() {
            return List.copyOf(messages);
        }
    }

    /**
     * Starts a segment at each user message; system messages, null entries and leading assistant
     * messages are excluded. A tool result belongs only to a preceding call in the same user turn;
     * orphan results are excluded, and ambiguous duplicate call/result IDs are rejected.
     * The counter receives each segment's read-only export once. Returned containers are read-only,
     * but retain the original message objects.
     */
    public static List<Segment> normalizeSegments(List<? extends Message> messages,
                                                  Function<List<Message>, Integer> tokenCount) {
        Objects.requireNonNull(tokenCount, "Token counter must not be null");
        if (messages == null || messages.isEmpty()) return List.of();

        List<Segment> segments = new ArrayList<>();
        Map<String, AiSegment> owners = new HashMap<>();
        Set<String> answered = new HashSet<>();
        Segment collecting = null;

        for (Message message : messages) {
            if (message == null || message instanceof SystemMessageEntity) continue;

            if (message instanceof UserMessageEntity user) {
                flush(collecting, tokenCount, segments);
                collecting = new Segment(user);
                owners.clear();
                answered.clear();
            } else if (collecting != null && message instanceof AiMessageEntity ai) {
                AiSegment segment = new AiSegment(ai);
                collecting.aiSegments.add(segment);
                collecting.messages.add(ai);
                if (ai.getToolCalls() == null) continue;
                for (ToolCallRequest call : ai.getToolCalls()) {
                    if (call == null || call.id() == null || call.id().isBlank()) continue;
                    if (owners.putIfAbsent(call.id(), segment) != null) {
                        throw new IllegalArgumentException("Duplicate tool call id in user turn: " + call.id());
                    }
                }
            } else if (collecting != null && message instanceof ToolMessageEntity tool) {
                String id = tool.getId() == null ? null : String.valueOf(tool.getId());
                AiSegment owner = owners.get(id);
                if (owner == null) continue;
                if (!answered.add(id)) {
                    throw new IllegalArgumentException("Duplicate tool result id in user turn: " + id);
                }
                owner.toolMessageEntities.add(tool);
                collecting.messages.add(tool);
            }
        }
        flush(collecting, tokenCount, segments);
        return List.copyOf(segments);
    }

    private static void flush(Segment collecting, Function<List<Message>, Integer> tokenCount,
                              List<Segment> segments) {
        if (collecting == null) return;
        Integer count = tokenCount.apply(collecting.export());
        if (count == null || count < 0) {
            throw new IllegalArgumentException("Token counter must return a non-negative count");
        }
        collecting.tokenCounts = count;
        segments.add(collecting);
    }
}
