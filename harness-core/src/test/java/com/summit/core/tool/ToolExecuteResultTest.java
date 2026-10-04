package com.summit.core.tool;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The result factories are the only place the {@code code} / {@code toolResultType} defaults are
 * decided: every overload that omits a type must fall back to {@code NORMAL}, never to {@code null}.
 */
class ToolExecuteResultTest {

    @Test
    void promiseIsASuccessfulPlaceholderResult() {
        ToolExecuteResult result = ToolExecuteResult.promise("waiting");

        assertTrue(result.isSuccess());
        assertTrue(result.isPromise());
        assertEquals(ToolResultType.PROMISE, result.getToolResultType());
        assertEquals("waiting", result.getToolOutput());
    }

    @Test
    void plainSuccessAndErrorFallBackToANormalResultType() {
        ToolExecuteResult ok = ToolExecuteResult.success("ok");
        assertTrue(ok.isSuccess());
        assertEquals(1, ok.getCode());
        assertEquals(ToolResultType.NORMAL, ok.getToolResultType());

        ToolExecuteResult failed = ToolExecuteResult.err("boom");
        assertFalse(failed.isSuccess());
        assertEquals(0, failed.getCode());
        assertEquals(ToolResultType.NORMAL, failed.getToolResultType());
        assertEquals("boom", failed.getToolOutput());
    }

    @Test
    void toolMetaDataRidesAlongOnSuccessErrorAndPromise() {
        Map<String, Object> metaData = Map.of("delegation", "sub-1");

        assertEquals(metaData,
                ToolExecuteResult.success("ok", ToolResultType.NORMAL, metaData).getToolMetaData());
        assertEquals(metaData,
                ToolExecuteResult.err("boom", ToolResultType.NORMAL, metaData).getToolMetaData());
        assertEquals(metaData, ToolExecuteResult.promise("waiting", metaData).getToolMetaData());
    }
}
