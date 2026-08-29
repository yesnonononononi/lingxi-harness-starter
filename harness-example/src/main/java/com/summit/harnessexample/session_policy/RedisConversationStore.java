package com.summit.harnessexample.session_policy;
import com.summit.harnesscore.conversation.ConversationEntity;
import com.summit.harnesscore.conversation.ConversationStore;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.types.Expiration;
import org.springframework.stereotype.Service;
import java.io.Serializable;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class RedisConversationStore implements ConversationStore {
    private final RedisTemplate<String, Object> redisTemplate;
    private final static long TTL = 36000;
    private final static ChronoUnit timeUnit = ChronoUnit.SECONDS;


    @Override
    public Optional<ConversationEntity> get(@NonNull Serializable sessionId) {
        // RedisTemplate 的 value serializer 已按 @class 还原为强类型对象，
        // 不能再用未开启多态类型信息的 ObjectMapper 做 convertValue（无法还原 Message 接口）
        Object raw = redisTemplate.opsForValue().get("session:id:" + sessionId);
        return Optional.ofNullable(raw instanceof ConversationEntity entity ? entity : null);
    }


    @Override
    public void save(@NonNull Serializable sessionId, @NonNull ConversationEntity conversation) {
        if(conversation.sessionId() == null){
            conversation = conversation.withSessionId(sessionId);
        }
        redisTemplate.opsForValue().set("session:id:" + sessionId, conversation, Expiration.from(Duration.of(TTL,timeUnit)));

    }

    @Override
    public Optional<ConversationEntity> removeAndReturn(@NonNull Serializable sessionId) {
        Optional<ConversationEntity> conversationEntity = this.get(sessionId);
        redisTemplate.delete("session:id:" + sessionId);
        return conversationEntity;
    }

    @Override
    public void remove(@NonNull Serializable sessionId) {
        redisTemplate.delete("session:id:" + sessionId);
    }

    @Override
    public void clear() {
        redisTemplate.delete("session:id");
    }

    @Override
    public Collection<ConversationEntity> list() {
        return redisTemplate.opsForValue().multiGet(redisTemplate.keys("session:id:*")).stream()
                .filter(ConversationEntity.class::isInstance)
                .map(ConversationEntity.class::cast)
                .collect(Collectors.toList());
    }
}
