package com.summit.harnessexample.session_policy;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Example application persistence; the harness itself has no conversation store. */
@Service
@RequiredArgsConstructor
public class RedisConversationRepository {
    private static final Duration TTL = Duration.ofHours(10);
    private static final String PREFIX = "conversation:id:";
    private final RedisTemplate<String, Object> redisTemplate;

    public Optional<ConversationRecord> find(String conversationId) {
        Object raw = redisTemplate.opsForValue().get(PREFIX + conversationId);
        return Optional.ofNullable(raw instanceof ConversationRecord record ? record : null);
    }

    public void save(ConversationRecord record) {
        redisTemplate.opsForValue().set(PREFIX + record.conversationId(), record, TTL);
    }

    public Optional<ConversationRecord> remove(String conversationId) {
        Optional<ConversationRecord> existing = find(conversationId);
        redisTemplate.delete(PREFIX + conversationId);
        return existing;
    }

    public List<ConversationRecord> findAll() {
        Set<String> keys = redisTemplate.keys(PREFIX + "*");
        if (keys == null || keys.isEmpty()) return List.of();
        List<Object> values = redisTemplate.opsForValue().multiGet(keys);
        if (values == null) return List.of();
        return values.stream().filter(ConversationRecord.class::isInstance)
                .map(ConversationRecord.class::cast).toList();
    }
}
