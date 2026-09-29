package com.summit.harness.springbootautoconfigure.config.json;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.summit.core.agent.Execution;
import com.summit.core.conversation.message.UserMessageEntity;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class JsonConfigTest {
    @Test
    void sharedMapperRestoresExecutionsAndStillReadsPlainToolArguments() throws Exception {
        ObjectMapper mapper = new JsonConfig().objectMapper();
        Execution original = Execution.builder().id("execution")
                .createAt(Instant.parse("2026-09-27T00:00:00Z"))
                .messages(List.of(UserMessageEntity.from("hello"))).build();

        Execution restored = mapper.readValue(mapper.writeValueAsString(original), Execution.class);

        assertEquals(original.getCreateAt(), restored.getCreateAt());
        assertEquals("hello", assertInstanceOf(UserMessageEntity.class, restored.getMessages().getFirst()).text());
        assertEquals(Map.of("path", "readme"), mapper.readValue("{\"path\":\"readme\"}", Map.class));
    }
}
