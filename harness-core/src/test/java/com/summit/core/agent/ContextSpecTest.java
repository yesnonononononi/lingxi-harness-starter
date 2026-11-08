package com.summit.core.agent;

import com.summit.core.conversation.message.UserMessageEntity;
import com.summit.core.json.ExecutionJson;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ContextSpecTest {
    @Test
    void roundTripsContextPromptAndMemoryWithoutLosingMessageTypes() throws Exception {
        var mapper = ExecutionJson.newObjectMapper();
        var request = AgentRequest.builder().contextSpec(ContextSpec.builder()
                .systemPrompt("role").context(List.of(UserMessageEntity.from("task")))
                .memory(List.of("project conventions", "user preferences")).build()).build();

        var restored = mapper.readValue(mapper.writeValueAsString(request), AgentRequest.class);

        assertEquals(request.getContextSpec().getMemory(), restored.getContextSpec().getMemory());
        assertEquals("role", restored.getContextSpec().getSystemPrompt());
        assertEquals("task", assertInstanceOf(UserMessageEntity.class,
                restored.getContextSpec().getContext().getFirst()).text());
        var execution = Execution.create(restored, "agent");
        execution.getMessages().add(UserMessageEntity.from("next"));
        assertEquals(1, restored.getContextSpec().getContext().size());
    }

    @Test
    void defaultsAreIndependentAndEmptyExecutionsRemainInvalid() throws Exception {
        var first = AgentRequest.builder().build();
        var second = ExecutionJson.newObjectMapper().readValue("{}", AgentRequest.class);
        assertNotSame(first.getContextSpec(), second.getContextSpec());
        assertEquals(List.of(), second.getContextSpec().getMemory());
        var error = assertThrows(IllegalArgumentException.class, () -> Execution.create(first, "agent"));
        assertTrue(error.getMessage().contains("contextSpec.context"));
        first.getContextSpec().getContext().add(UserMessageEntity.from("task"));
        assertTrue(second.getContextSpec().getContext().isEmpty());
    }
}
