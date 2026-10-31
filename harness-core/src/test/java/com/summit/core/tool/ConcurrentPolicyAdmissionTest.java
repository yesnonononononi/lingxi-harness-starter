package com.summit.core.tool;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the fail-safe admission rule: only a policy that explicitly declares concurrency may
 * join a concurrent batch. A missing policy must never widen to "concurrent", otherwise every
 * tool that forgets to declare one silently becomes a shared-state writer running in parallel.
 */
class ConcurrentPolicyAdmissionTest {

    @Test
    void missingPolicyIsTreatedAsSerial() {
        ToolDefinition<ToolExecutor> definition = definition(null);

        assertFalse(definition.allowConcurrent());
        assertFalse(definition.readOnly());
    }

    @Test
    void serialPoliciesAreNotConcurrent() {
        assertFalse(definition(ConcurrentPolicy.SERIAL).allowConcurrent());
        assertFalse(definition(ConcurrentPolicy.SERIAL_MUTATION).allowConcurrent());
    }

    @Test
    void onlyExplicitConcurrencyPoliciesAreAdmitted() {
        ToolDefinition<ToolExecutor> readOnly = definition(ConcurrentPolicy.READ_ONLY);
        assertTrue(readOnly.allowConcurrent());
        assertTrue(readOnly.readOnly());

        ToolDefinition<ToolExecutor> isolated = definition(ConcurrentPolicy.ISOLATED_MUTATION);
        assertTrue(isolated.allowConcurrent());
        assertFalse(isolated.readOnly());
    }

    private ToolDefinition<ToolExecutor> definition(ConcurrentPolicy policy) {
        return ToolDefinition.<ToolExecutor>builder()
                .id("id").name("tool").description("tool")
                .parametersJsonSchema("{\"type\":\"object\",\"properties\":{}}")
                .executor(execution -> ToolExecuteResult.success("ok"))
                .maxOutput(100).timeout(2L).concurrentPolicy(policy)
                .build();
    }
}
