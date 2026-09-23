package com.summit.harness.springbootautoconfigure.conf;

import com.summit.harness.springbootautoconfigure.conf.sys.ExecutionRuntimeConfig;
import com.summit.runtime.loop.control.InMemoryActiveExecutionRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class ExecutionRuntimeConfigTest {
    private final ExecutionRuntimeConfig configuration = new ExecutionRuntimeConfig();

    /**
     * The fallback is the only branch this config can decide on its own. Precedence for an
     * application-supplied {@code ExecutionRepository} is expressed declaratively through
     * {@code @ConditionalOnMissingBean} rather than by branching in code.
     */
    @Test
    void fallbackIsAnInMemoryRegistry() {
        assertInstanceOf(InMemoryActiveExecutionRegistry.class,
                configuration.inMemoryExecutionRepository());
    }
}
