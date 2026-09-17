package com.summit.runtime.toolSupport;

import com.summit.core.interceptor.InvocationContext;
import com.summit.core.tool.ToolExecuteResult;
import com.summit.core.tool.ToolExecution;
import com.summit.core.tool.ToolExecutor;
import com.summit.core.tool.ToolDefinition;
import com.summit.core.tool.ToolResultType;
import com.summit.runtime.conversation.DefaultTokenizer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultToolInterceptorTest {

    private static final ToolExecutor NOOP_EXECUTOR = execution -> null;

    /**
     * The compaction result is the summary itself: truncating it leaves an unbalanced JSON that no
     * longer resolves, so the context never shrinks and the agent keeps calling compact_context.
     */
    @Test
    void compactOutputIsNeverTruncated() {
        String json = """
                {"goal": "g", "summary": "%s", "completed": ["task-1"], "pending": ["task-2"], "state": "DONE"}
                """.formatted("x".repeat(3000));

        ToolDefinition<ToolExecutor> definition = definition();
        ToolExecuteResult result = ToolExecuteResult.success(json,
                ToolResultType.CONTEXT_COMPACT);

        new DefaultToolInterceptor(new DefaultTokenizer()).after(context(definition), result);

        assertEquals(json, result.getToolOutput());
    }

    @Test
    void ordinaryToolOutputIsStillTruncatedToTheToolBudget() {
        String output = "y".repeat(3000);

        ToolDefinition<ToolExecutor> definition = definition();
        ToolExecuteResult result = ToolExecuteResult.success(output);

        new DefaultToolInterceptor(new DefaultTokenizer()).after(context(definition), result);

        assertTrue(result.getToolOutput().length() < output.length(),
                "a normal tool result stays bounded by the tool output budget");
        assertTrue(result.getToolOutput().contains("OUTPUT_TRUNCATED"));
    }

    private static ToolDefinition<ToolExecutor> definition() {
        return ToolDefinition.<ToolExecutor>builder()
                .id("tool")
                .name("tool")
                .executor(NOOP_EXECUTOR)
                .maxOutput(10)
                .timeout(30L)
                .build();
    }

    private static InvocationContext<ToolExecution> context(ToolDefinition<ToolExecutor> definition) {
        return InvocationContext.<ToolExecution>builder()
                .context(ToolExecution.builder()
                        .id("call-1")
                        .toolDefinition(definition)
                        .sessionId("session-1")
                        .turnId("turn-1")
                        .build())
                .build();
    }
}
