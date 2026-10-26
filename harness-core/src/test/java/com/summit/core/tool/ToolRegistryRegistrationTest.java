package com.summit.core.tool;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ToolRegistryRegistrationTest {

    @Test
    void registrationUpdatesLookupAndCommandResolution() {
        ToolDefinition<ToolExecutor> existing = definition("existing", "existing-id");
        ToolRegistry registry = new ToolRegistry(new ArrayList<>(List.of(existing)));
        ToolDefinition<ToolExecutor> added = definition("added", "added-id");

        registry.register(added);

        assertSame(existing, registry.getTool("existing"));
        assertSame(added, registry.getTool("added"));
        assertSame(added, ToolExecuteCommand.builder().build().resolve(registry, "added"));
        assertEquals(List.of(existing, added), registry.getToolDefinitionList());
        assertSame(added, registry.getToolById("added-id"));
    }

    @Test
    void duplicateNamesDoNotReplaceTheOriginal() {
        ToolDefinition<ToolExecutor> original = definition("shared", "original-id");
        ToolRegistry registry = new ToolRegistry(new ArrayList<>(List.of(original)));

        RuntimeException error = assertThrows(RuntimeException.class,
                () -> registry.register(definition("shared", "different-id")));

        assertTrue(error.getMessage().contains("shared"));
        assertSame(original, registry.getTool("shared"));
        assertTrue(error.getMessage().contains("original-id"));
        assertTrue(error.getMessage().contains("different-id"));
        assertEquals(List.of(original), registry.getToolDefinitionList());
    }

    @Test
    void nullDefinitionDoesNotChangeTheRegistry() {
        ToolDefinition<ToolExecutor> existing = definition("existing", "existing-id");
        ToolRegistry registry = new ToolRegistry(new ArrayList<>(List.of(existing)));

        assertThrows(NullPointerException.class, () -> registry.register((ToolDefinition<?>) null));

        assertSame(existing, registry.getTool("existing"));
    }

    @Test
    void unregisterRemovesLookupAndAllowsRegisteringAReplacement() {
        ToolDefinition<ToolExecutor> original = definition("replaceable", "original-id");
        ToolDefinition<ToolExecutor> retained = definition("retained", "retained-id");
        ToolRegistry registry = new ToolRegistry(new ArrayList<>(List.of(original, retained)));

        registry.unRegister("replaceable");

        assertNull(registry.getTool("replaceable"));
        assertSame(retained, registry.getTool("retained"));
        assertNull(ToolExecuteCommand.builder().build().resolve(registry, "replaceable"));
        ToolDefinition<ToolExecutor> replacement = definition("replaceable", "replacement-id");
        registry.register(replacement);
        assertSame(replacement, registry.getTool("replaceable"));
    }

    @Test
    void unregisteringAnUnknownNameLeavesExistingToolsUntouched() {
        ToolDefinition<ToolExecutor> existing = definition("existing", "existing-id");
        ToolRegistry registry = new ToolRegistry(new ArrayList<>(List.of(existing)));

        registry.unRegister("unknown");

        assertSame(existing, registry.getTool("existing"));
        assertNull(registry.getTool("unknown"));
    }

    @Test
    void registrationAcceptsAnImmutableInitialListWithoutPartiallyUpdatingLookup() {
        ToolRegistry registry = new ToolRegistry(List.of());
        ToolDefinition<ToolExecutor> added = definition("added", "added-id");

        assertDoesNotThrow(() -> registry.register(added));

        assertSame(added, registry.getTool("added"));
    }

    @Test
    void unregisterAcceptsAnImmutableInitialList() {
        ToolDefinition<ToolExecutor> existing = definition("existing", "existing-id");
        ToolRegistry registry = new ToolRegistry(List.of(existing));

        assertDoesNotThrow(() -> registry.unRegister("existing"));

        assertNull(registry.getTool("existing"));
    }

    @Test
    void repeatedIdKeepsTheFirstDefinitionEvenWhenTheNameChanges() {
        ToolDefinition<ToolExecutor> original = definition("original", "shared-id");
        ToolRegistry registry = new ToolRegistry(List.of(original));

        assertDoesNotThrow(() -> registry.register(original));
        assertDoesNotThrow(() -> registry.register(definition("renamed", "shared-id")));

        assertSame(original, registry.getToolById("shared-id"));
        assertSame(original, registry.getTool("original"));
        assertNull(registry.getTool("renamed"));
        assertEquals(List.of(original), registry.getToolDefinitionList());
        assertEquals(Map.of("original", original), registry.getTools());
    }

    @Test
    void constructorAppliesTheSameIdAndNameRulesAsLaterRegistration() {
        ToolDefinition<ToolExecutor> original = definition("original", "shared-id");
        ToolRegistry registry = new ToolRegistry(List.of(original, definition("renamed", "shared-id")));

        assertEquals(List.of(original), registry.getToolDefinitionList());
        assertSame(original, registry.getToolById("shared-id"));
        assertNull(registry.getTool("renamed"));
        assertThrows(IllegalArgumentException.class,
                () -> new ToolRegistry(List.of(original, definition("original", "different-id"))));
    }

    @Test
    void bulkRegistrationCompletesAndDeduplicatesAgainstExistingAndBatchEntries() {
        ToolDefinition<ToolExecutor> original = definition("original", "original-id");
        ToolDefinition<ToolExecutor> first = definition("first", "first-id");
        ToolDefinition<ToolExecutor> second = definition("second", "second-id");
        ToolRegistry registry = new ToolRegistry(List.of(original));

        assertDoesNotThrow(() -> registry.register(List.of()));
        assertDoesNotThrow(() -> registry.register(List.of(
                definition("alias", "original-id"), first, first, second)));

        assertEquals(List.of(original, first, second), registry.getToolDefinitionList());
        assertEquals(3, registry.getTools().size());
        assertSame(first, registry.getTool("first"));
        assertSame(second, registry.getToolById("second-id"));
        assertNull(registry.getTool("alias"));
    }

    @Test
    void unregisterReleasesTheIdForAChangedName() {
        ToolRegistry registry = new ToolRegistry(List.of(definition("original", "reused-id")));

        registry.unRegister("original");
        assertNull(registry.getToolById("reused-id"));
        ToolDefinition<ToolExecutor> replacement = definition("renamed", "reused-id");
        registry.register(replacement);

        assertSame(replacement, registry.getToolById("reused-id"));
        assertNull(registry.getTool("original"));
        assertEquals(List.of(replacement), registry.getToolDefinitionList());
    }

    @Test
    void constructorAndReadSnapshotsCannotMutateRegistryState() {
        ToolDefinition<ToolExecutor> original = definition("original", "original-id");
        List<ToolDefinition<? extends ToolExecutor>> input = new ArrayList<>(List.of(original));
        ToolRegistry registry = new ToolRegistry(input);
        List<ToolDefinition<? extends ToolExecutor>> listSnapshot = registry.getToolDefinitionList();
        Map<String, ToolDefinition<? extends ToolExecutor>> mapSnapshot = registry.getTools();

        input.clear();
        assertThrows(UnsupportedOperationException.class, listSnapshot::clear);
        assertThrows(UnsupportedOperationException.class, mapSnapshot::clear);
        ToolDefinition<ToolExecutor> added = definition("added", "added-id");
        registry.register(added);

        assertEquals(List.of(original), listSnapshot);
        assertEquals(Map.of("original", original), mapSnapshot);
        assertEquals(List.of(original, added), registry.getToolDefinitionList());
        assertSame(original, registry.getTool("original"));
    }

    @Test
    void concurrentRegistrationOfOneIdAddsExactlyOneDefinition() throws Exception {
        ToolRegistry registry = new ToolRegistry(List.of());
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> registrations = new ArrayList<>();
        try (ExecutorService executor = Executors.newFixedThreadPool(8)) {
            for (int index = 0; index < 24; index++) {
                ToolDefinition<ToolExecutor> candidate = definition("candidate_" + index, "shared-id");
                registrations.add(executor.submit(() -> {
                    try {
                        start.await();
                    } catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(error);
                    }
                    registry.register(candidate);
                }));
            }
            start.countDown();
            for (Future<?> registration : registrations) {
                registration.get(10, TimeUnit.SECONDS);
            }
        }

        ToolDefinition<?> registered = registry.getToolById("shared-id");
        assertNotNull(registered);
        assertEquals(List.of(registered), registry.getToolDefinitionList());
        assertEquals(Map.of(registered.name(), registered), registry.getTools());
    }

    private ToolDefinition<ToolExecutor> definition(String name, String id) {
        return ToolDefinition.<ToolExecutor>builder()
                .id(id).name(name).description("Tool " + name)
                .parametersJsonSchema("{\"type\":\"object\",\"properties\":{}}")
                .executor(execution -> ToolExecuteResult.success(name))
                .maxOutput(100).timeout(2L).concurrentPolicy(ConcurrentPolicy.READ_ONLY)
                .build();
    }
}
