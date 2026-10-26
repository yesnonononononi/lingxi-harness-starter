package com.summit.core.tool;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

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
    }

    @Test
    void duplicateNamesDoNotReplaceTheOriginal() {
        ToolDefinition<ToolExecutor> original = definition("shared", "original-id");
        ToolRegistry registry = new ToolRegistry(new ArrayList<>(List.of(original)));

        RuntimeException error = assertThrows(RuntimeException.class,
                () -> registry.register(definition("shared", "different-id")));

        assertTrue(error.getMessage().contains("shared"));
        assertSame(original, registry.getTool("shared"));
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

    private ToolDefinition<ToolExecutor> definition(String name, String id) {
        return ToolDefinition.<ToolExecutor>builder()
                .id(id).name(name).description("Tool " + name)
                .parametersJsonSchema("{\"type\":\"object\",\"properties\":{}}")
                .executor(execution -> ToolExecuteResult.success(name))
                .maxOutput(100).timeout(2L).concurrentPolicy(ConcurrentPolicy.READ_ONLY)
                .build();
    }
}
