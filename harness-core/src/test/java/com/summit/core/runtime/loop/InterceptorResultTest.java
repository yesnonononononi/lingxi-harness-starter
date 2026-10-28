package com.summit.core.runtime.loop;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InterceptorResultTest {
    @Test
    void delegationAndExplicitContinuationHaveDifferentDispatchDecisions() {
        InterceptorResult continuation = InterceptorResult.of(LoopResult.continueLoop(), false);

        assertTrue(InterceptorResult.NONE.next());
        assertTrue(InterceptorResult.NONE.loopResult().shouldContinue());
        assertFalse(continuation.next());
        assertTrue(continuation.loopResult().shouldContinue());
    }

    @ParameterizedTest
    @EnumSource(value = LoopResult.Status.class, names = {"CANCELLED", "SUSPENDED", "COMPLETED"})
    void terminalDecisionsCannotDelegateToAnotherInterceptor(LoopResult.Status status) {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> new InterceptorResult(new LoopResult(status, "decision"), true));

        assertTrue(failure.getMessage().contains(status.name()));
    }

    @ParameterizedTest
    @EnumSource(value = LoopResult.Status.class, names = {"CANCELLED", "SUSPENDED", "COMPLETED"})
    void explicitTerminalDecisionsRetainTheirResult(LoopResult.Status status) {
        LoopResult decision = new LoopResult(status, "decision");
        InterceptorResult result = InterceptorResult.of(decision, false);

        assertFalse(result.next());
        assertEquals(decision, result.loopResult());
    }

    @Test
    void nullLoopResultsKeepTheContinueDefaultWithoutChangingDelegation() {
        assertTrue(InterceptorResult.of(null, true).next());
        assertFalse(InterceptorResult.of(null, false).next());
        assertTrue(InterceptorResult.of(null, true).loopResult().shouldContinue());
        assertTrue(InterceptorResult.of(null, false).loopResult().shouldContinue());
    }
}
