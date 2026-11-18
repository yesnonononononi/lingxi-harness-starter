package com.summit.core.conversation.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The four wire fields must come from the event surface itself.
 *
 * <p>They used to reach the wire because every host registered a Jackson mix-in on its own mapper,
 * and one host that forgot it silently produced frames with no {@code type} — a whole class of "the
 * event arrived but the client could not dispatch it" bugs. An event handed to a plain
 * {@code ObjectMapper} (with nothing but the standard Java-time support) has to serialise the same
 * way.</p>
 */
class EventWireContractTest {

    /** Deliberately a bare mapper: no mix-in, no framework module, nothing event-specific. */
    private final ObjectMapper plain = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    void everyWireFieldComesFromTheEventSurfaceItself() {
        Event event = ContextChangedEvent.builder()
                .executionId("e-1")
                .metaData(Map.of("turnId", "7"))
                .contextRevision(3)
                .build();

        ObjectNode json = plain.valueToTree(event);

        assertEquals("CONTEXT_CHANGED", json.path("type").asText(),
                "a plain mapper must still see the discriminator");
        assertEquals("e-1", json.path("executionId").asText());
        assertEquals("7", json.path("metaData").path("turnId").asText());
        assertTrue(json.hasNonNull("timestamp"));
        assertEquals(3, json.path("contextRevision").asLong(),
                "the fact identity has to survive a host that knows nothing about the framework");
    }

    @Test
    void lifecycleEventsShareTheSameWireNames() {
        Event lifecycle = new ExecutionStartEvent("e-2");

        ObjectNode json = plain.valueToTree(lifecycle);

        assertEquals("EXECUTION_STARTED", json.path("type").asText());
        assertEquals("e-2", json.path("executionId").asText());
        assertTrue(json.hasNonNull("timestamp"));
    }
}
