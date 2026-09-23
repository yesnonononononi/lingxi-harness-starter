package com.summit.core.tool;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolExecuteResultTest {

    @Test
    void promiseIsASuccessfulPlaceholderResult() {
        ToolExecuteResult result = ToolExecuteResult.promise("waiting");

        assertTrue(result.isSuccess());
        assertTrue(result.isPromise());
        assertEquals(ToolResultType.PROMISE, result.getToolResultType());
        assertEquals("waiting", result.getToolOutput());
    }
}
