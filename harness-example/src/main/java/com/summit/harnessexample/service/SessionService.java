package com.summit.harnessexample.service;

import com.summit.core.conversation.ConversationEntity;
import com.summit.core.conversation.message.AiMessageEntity;
import com.summit.core.conversation.message.Message;
import com.summit.core.conversation.message.ToolMessageEntity;
import com.summit.core.conversation.message.UserMessageEntity;
import com.summit.harnessexample.common.ApiException;
import com.summit.harnessexample.session_policy.RedisConversationStore;
import com.summit.harnessexample.session_policy.SessionSummary;
import com.summit.runtime.internalUtils.PlanKernel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Read / write operations on the persisted conversations: listing, message history,
 * rename and delete. Also owns the display-name fallback used when a session has no
 * explicit name.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SessionService {

    private final RedisConversationStore conversationStore;
    private final PlanKernel planKernel;

    /** Lightweight summaries of every stored session. */
    public Map<String, Object> list() {
        List<Map<String, Object>> sessions = new ArrayList<>();
        for (SessionSummary summary : conversationStore.sessionSummaries()) {
            Map<String, Object> session = new LinkedHashMap<>();
            session.put("sessionId", summary.sessionId());
            session.put("sessionName", summary.sessionName() == null || summary.sessionName().isBlank()
                    ? defaultSessionName(String.valueOf(summary.sessionId()))
                    : summary.sessionName());
            sessions.add(session);
        }
        return Map.of("sessions", sessions);
    }

    /** Message history of one session, mapped to display DTOs (USER / AI / TOOL). */
    public Map<String, Object> messages(String sessionId) {
        ConversationEntity entity = conversationStore.get(sessionId)
                .orElseThrow(() -> ApiException.notFound("session not found: " + sessionId));

        List<Map<String, Object>> messages = new ArrayList<>();
        for (Message message : entity.messages()) {
            Map<String, Object> item = new LinkedHashMap<>();
            if (message instanceof UserMessageEntity user) {
                item.put("role", "USER");
                item.put("text", user.text());
            } else if (message instanceof AiMessageEntity ai) {
                item.put("role", "AI");
                item.put("text", ai.text());
                item.put("thinking", ai.getThinking());
            } else if (message instanceof ToolMessageEntity tool) {
                item.put("role", "TOOL");
                item.put("toolName", tool.getName());
                item.put("text", tool.text());
            } else {
                continue;
            }
            messages.add(item);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("sessionId", sessionId);
        data.put("sessionName", entity.sessionName());
        data.put("messages", messages);
        return data;
    }

    /** Renames a session; a blank new name is rejected (the name is user-visible). */
    public Map<String, Object> rename(String sessionId, String sessionName) {
        if (isBlank(sessionId) || isBlank(sessionName)) {
            throw ApiException.badRequest("sessionId and sessionName must not be blank");
        }
        ConversationEntity existing = conversationStore.get(sessionId)
                .orElseThrow(() -> ApiException.notFound("session not found: " + sessionId));
        conversationStore.save(sessionId, existing.withSessionName(sessionName));

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("sessionId", sessionId);
        data.put("sessionName", sessionName);
        return data;
    }

    /** Deletes a conversation and its plan; idempotent, reports whether something was removed. */
    public Map<String, Object> delete(String sessionId) {
        if (isBlank(sessionId)) {
            throw ApiException.badRequest("sessionId must not be blank");
        }
        Optional<ConversationEntity> removed = conversationStore.removeAndReturn(sessionId);
        Optional<?> plan = planKernel.delete(sessionId);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("sessionId", sessionId);
        data.put("deleted", removed.isPresent());
        data.put("planDeleted", plan.isPresent());
        return data;
    }

    /** Total number of stored sessions (health / dashboards). */
    public int count() {
        return conversationStore.sessionSummaries().size();
    }

    /** Collapses whitespace and truncates the first instruction into a session title. */
    public static String defaultSessionName(String input) {
        String name = String.valueOf(input).replaceAll("\\s+", " ").trim();
        return name.length() > 20 ? name.substring(0, 20) + "..." : name;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
