package com.summit.core.conversation.event;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.summit.core.conversation.ChangedDiff;
import com.summit.core.conversation.message.ContextSectionKind;
import com.summit.core.json.ExecutionJson;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a context-change fact is allowed to claim.
 *
 * <p>{@code prefixPreserved} used to be a second boolean maintained next to the reasons, and the two
 * could disagree — a "protected" flag that was neither derived nor trustworthy. It is now a pure
 * function of {@link ContextChangeReason}, so these tests own the derivation and the wire format: a
 * transport that renders this event must see {@code prefixPreserved} and must not see a stale
 * {@code protected} field.</p>
 */
class ContextChangedEventTest {

    @Test
    void prefixSurvivesOnlyWhenEveryReasonIsADynamicSectionChange() {
        assertTrue(event(Set.of(ContextChangeReason.DYNAMIC_SECTIONS_CHANGED)).prefixPreserved(),
                "appending behind the prefix leaves the cache intact");
        assertFalse(event(Set.of(ContextChangeReason.SYSTEM_PROMPT_REPLACED)).prefixPreserved(),
                "rewriting the leading system prompt drops every cached token after it");
        assertFalse(event(Set.of(ContextChangeReason.SYSTEM_PROMPT_INSERTED)).prefixPreserved());
        assertFalse(event(Set.of(ContextChangeReason.SYSTEM_PROMPT_INSERTED,
                ContextChangeReason.DYNAMIC_SECTIONS_CHANGED)).prefixPreserved(),
                "one prefix-breaking reason is enough");
    }

    @Test
    void reasonsAreNeverEmptyForAPublishedEvent() {
        assertEquals(Set.of(ContextChangeReason.DYNAMIC_SECTIONS_CHANGED),
                ContextChangedEvent.builder().executionId("execution").build().getReasons());
    }

    @Test
    void aFirstRoundSectionCarriesNoOldContent() {
        Map<ContextSectionKind, ChangedDiff> sections = new EnumMap<>(ContextSectionKind.class);
        sections.put(ContextSectionKind.MEMORY, new ChangedDiff(null, "new"));

        ContextChangedEvent event = ContextChangedEvent.builder()
                .executionId("execution").changedSections(sections).build();

        assertNull(event.getChangedSections().get(ContextSectionKind.MEMORY).oldContent(),
                "a section that did not exist before has nothing to diff against");
    }

    @Test
    void changedSectionsAreAnOwnedReadOnlyEnumProfile() {
        Map<ContextSectionKind, ChangedDiff> sections = new EnumMap<>(ContextSectionKind.class);
        sections.put(ContextSectionKind.MEMORY, new ChangedDiff(null, "new"));

        ContextChangedEvent event = ContextChangedEvent.builder()
                .executionId("execution").changedSections(sections).build();
        sections.clear();

        assertEquals(1, event.getChangedSections().size(), "the event owns a copy, not the caller's map");
        assertThrows(UnsupportedOperationException.class,
                () -> event.getChangedSections().put(ContextSectionKind.TASK, new ChangedDiff("y", "x")));
    }

    @Test
    void theWireFormatExposesPrefixPreservedAndNoStaleProtectedFlag() throws Exception {
        ObjectMapper mapper = ExecutionJson.newObjectMapper();
        ContextChangedEvent event = ContextChangedEvent.builder()
                .executionId("execution")
                .metaData(Map.of("turnId", "1"))
                .reasons(Set.of(ContextChangeReason.SYSTEM_PROMPT_REPLACED))
                .currentLiveBlocks(2)
                .maxLiveBlocks(4)
                .build();

        JsonNode json = mapper.readTree(mapper.writeValueAsString(event));

        assertTrue(json.has("prefixPreserved"), "the transport needs the derived flag: " + json);
        assertFalse(json.get("prefixPreserved").asBoolean());
        assertFalse(json.has("protected"), "the old drifted boolean must not survive on the wire: " + json);
        assertEquals(2, json.get("currentLiveBlocks").asInt());
        assertEquals(4, json.get("maxLiveBlocks").asInt());
        assertEquals("SYSTEM_PROMPT_REPLACED", json.get("reasons").get(0).asText());
    }

    private static ContextChangedEvent event(Set<ContextChangeReason> reasons) {
        return ContextChangedEvent.builder().executionId("execution").reasons(reasons).build();
    }
}
