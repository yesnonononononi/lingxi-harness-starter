package com.summit.runtime.compact;

import com.summit.core.conversation.ConversationManager;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompactSummaryResolverTest {

    @Test
    void resolvesAWellFormedSummaryObject() {
        String raw = """
                {
                  "goal": "实现模型配置 CRUD",
                  "summary": "已完成后端命令合并",
                  "completed": ["task-1", "task-2"],
                  "pending": ["task-3"],
                  "state": "DONE"
                }
                """;

        ContextSummary summary = CompactSummaryResolver.resolve(raw);

        assertNotNull(summary);
        assertEquals("实现模型配置 CRUD", summary.getGoal());
        assertEquals("已完成后端命令合并", summary.getSummary());
        assertEquals(List.of("task-1", "task-2"), summary.getCompleted());
        assertEquals(List.of("task-3"), summary.getPending());
        assertEquals("DONE", summary.getState());
    }

    /**
     * A summary that was cut in half (the tool output cap) has to be salvaged, and the legacy
     * {@code completed[]} / {@code pending[]} aliases must not break the salvage: they used to be
     * interpolated into a regex verbatim, throwing {@code PatternSyntaxException}, which turned the
     * whole summary into {@code null} and left the agent loop compacting over and over again.
     */
    @Test
    void salvageOfATruncatedOutputNeverFails() {
        String truncated = "{\"goal\": \"实现模型配置 CRUD\", \"summary\": \"已完成后端命令合并\", "
                + "\"completed[]\": [\"task-1\", \"task-2\"], \"pending[]\": [\"task-";

        ContextSummary summary = CompactSummaryResolver.resolve(truncated);

        assertNotNull(summary);
        assertEquals("实现模型配置 CRUD", summary.getGoal());
        assertEquals("已完成后端命令合并", summary.getSummary());
        assertEquals(List.of("task-1", "task-2"), summary.getCompleted());
    }

    @Test
    void typographicQuotesInsideAValueDoNotBreakTheJson() {
        String raw = "{\"goal\": \"g\", \"summary\": \"新增“添加自定义模型”入口\", \"state\": \"DONE\"}";

        ContextSummary summary = CompactSummaryResolver.resolve(raw);

        assertNotNull(summary);
        assertEquals("新增“添加自定义模型”入口", summary.getSummary());
    }

    @Test
    void proseWithoutAnyJsonFallsBackToTheRawText() {
        ContextSummary summary = CompactSummaryResolver.resolve("这是一段没有任何结构的纯文本摘要");

        assertNotNull(summary);
        assertEquals("这是一段没有任何结构的纯文本摘要", summary.getSummary());
    }

    @Test
    void blankOutputResolvesToNull() {
        assertNull(CompactSummaryResolver.resolve(null));
        assertNull(CompactSummaryResolver.resolve("   "));
    }

    /**
     * The rebuild contract takes text, so a session can be rebuilt from any summary source. These
     * cover the two ends of that contract: a blank output must leave the context alone rather than
     * rebuild it from nothing, and a usable one must reach the manager already rendered.
     */
    @Test
    void blankOutputLeavesTheContextUntouched() {
        List<String> rebuilt = new ArrayList<>();
        ConversationManager manager = stubManager(rebuilt);

        assertFalse(new CompactSummaryApplier(manager).apply("   ", null, false));
        assertTrue(rebuilt.isEmpty());
    }

    @Test
    void aUsableSummaryReachesTheManagerAsRenderedText() {
        List<String> rebuilt = new ArrayList<>();
        ConversationManager manager = stubManager(rebuilt);
        String raw = "{\"goal\":\"g\",\"summary\":\"s\",\"completed\":[\"c\"],\"pending\":[],\"state\":\"DONE\"}";

        assertTrue(new CompactSummaryApplier(manager).apply(raw, null, true));
        assertEquals(1, rebuilt.size());
        assertTrue(rebuilt.getFirst().contains("summary:\ns"), rebuilt.getFirst());
        assertTrue(rebuilt.getFirst().contains("completed task:\n[c]"), rebuilt.getFirst());
    }

    private static ConversationManager stubManager(List<String> rebuilt) {
        return (ConversationManager) Proxy.newProxyInstance(
                ConversationManager.class.getClassLoader(),
                new Class<?>[]{ConversationManager.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("rebuildContext")) {
                        rebuilt.add((String) args[0]);
                    }
                    return null;
                });
    }
}
