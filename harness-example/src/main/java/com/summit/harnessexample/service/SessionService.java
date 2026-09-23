package com.summit.harnessexample.service;

import com.summit.core.compact.ContextUsageMetric;
import com.summit.core.compact.Tokenizer;
import com.summit.core.conversation.message.Message;
import com.summit.core.conversation.message.SystemMessageEntity;
import com.summit.harnessexample.common.ApiException;
import com.summit.harnessexample.session_policy.ConversationRecord;
import com.summit.harnessexample.session_policy.RedisConversationRepository;
import com.summit.runtime.agent.AgentConfig;
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

    private final RedisConversationRepository conversationRepository;
    private final Tokenizer tokenizer;
    private final AgentConfig agentConfig;

    /** Lightweight summaries of every stored session. */
    public Map<String, Object> list() {
        List<Map<String, Object>> sessions = new ArrayList<>();
        for (ConversationRecord summary : conversationRepository.findAll()) {
            Map<String, Object> session = new LinkedHashMap<>();
            session.put("sessionId", summary.conversationId());
            session.put("sessionName", summary.name() == null || summary.name().isBlank()
                    ? defaultSessionName(summary.conversationId()) : summary.name());
            sessions.add(session);
        }
        return Map.of("sessions", sessions);
    }

    /**
     * Message history of one session. The framework {@link Message} entities are returned as-is:
     * each one carries its own {@code type} (USER / AI / TOOL / SYSTEM), so the front-end renders
     * exactly what the framework persists instead of an example-private display DTO.
     */
    public Map<String, Object> messages(String sessionId) {
        ConversationRecord entity = conversationRepository.find(sessionId)
                .orElseThrow(() -> ApiException.notFound("session not found: " + sessionId));

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("sessionId", sessionId);
        data.put("sessionName", entity.name());
        data.put("messages", entity.messages().stream()
                .filter(this::isDisplayMessage)
                .toList());
        return data;
    }

    /**
     * Keeps system prompts out of user-visible history: framework-injected instructions travel as
     * {@link SystemMessageEntity}, so the history shows only what the user and the assistant said.
     */
    private boolean isDisplayMessage(Message message) {
        return !(message instanceof SystemMessageEntity);
    }

    /**
     * Current context usage of one session: how many tokens the stored conversation already
     * occupies against the configured cap. A blank / unknown session yields a zero count against
     * the same cap, so the front-end gauge renders an empty ring instead of disappearing.
     */
    public Map<String, Object> usage(String sessionId) {
        List<Message> messages = List.of();
        if (!isBlank(sessionId)) {
            messages = conversationRepository.find(sessionId)
                    .map(ConversationRecord::messages)
                    .orElseGet(List::of);
        }

        ContextUsageMetric metric = tokenizer.usage(messages, agentConfig.maxTokens());

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("sessionId", sessionId);
        data.put("tokenCount", metric == null ? tokenizer.count(messages) : metric.tokenCount());
        data.put("maxTokens", metric == null ? 0 : metric.maxTokens());
        data.put("ratio", metric == null ? 0d : metric.ratio());
        return data;
    }

    /** Renames a session; a blank new name is rejected (the name is user-visible). */
    public Map<String, Object> rename(String sessionId, String sessionName) {
        if (isBlank(sessionId) || isBlank(sessionName)) {
            throw ApiException.badRequest("sessionId and sessionName must not be blank");
        }
        ConversationRecord existing = conversationRepository.find(sessionId)
                .orElseThrow(() -> ApiException.notFound("session not found: " + sessionId));
        conversationRepository.save(existing.withName(sessionName));

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("sessionId", sessionId);
        data.put("sessionName", sessionName);
        return data;
    }

    /** Deletes a conversation; idempotent, reports whether something was removed. */
    public Map<String, Object> delete(String sessionId) {
        if (isBlank(sessionId)) {
            throw ApiException.badRequest("sessionId must not be blank");
        }
        Optional<ConversationRecord> removed = conversationRepository.remove(sessionId);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("sessionId", sessionId);
        data.put("deleted", removed.isPresent());
        return data;
    }

    /** Total number of stored sessions (health / dashboards). */
    public int count() {
        return conversationRepository.findAll().size();
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
