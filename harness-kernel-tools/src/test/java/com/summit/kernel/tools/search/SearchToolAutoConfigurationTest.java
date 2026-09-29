package com.summit.kernel.tools.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.summit.core.mcp.McpToolScope;
import com.summit.core.tool.ConcurrentPolicy;
import com.summit.core.tool.ToolDefinition;
import com.summit.core.tool.ToolExecuteResult;
import com.summit.core.tool.ToolExecution;
import com.summit.core.tool.ToolRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 内核工具的配置真的落到行为上。
 *
 * <p>「支持 yaml 配置」只有在配置被读进去、并且改变了可观察行为时才算数。这里直接驱动装配方法，
 * 不需要启动 Spring：属性对象 → {@code ToolDefinition} → 实际执行一次检索，全程可断言。</p>
 */
class SearchToolAutoConfigurationTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("max-matches 决定单次检索返回条数")
    void maxMatchesReachesTheExecutor() throws Exception {
        SearchToolProperties properties = new SearchToolProperties();
        properties.setMaxMatches(2);

        ToolDefinition<SearchToolExecutor> definition = new SearchToolAutoConfiguration()
                .searchToolDefinition(objectMapper, provider(registryOf(5)), properties);

        JsonNode body = objectMapper.readTree(
                definition.executor().execute(execution("")).getToolOutput());

        assertEquals(5, body.get("total").asInt(), "total 仍是真实全量");
        assertEquals(2, body.get("returned").asInt(), "返回条数取自配置");
        assertEquals(2, body.get("tools").size());
    }

    @Test
    @DisplayName("timeout 决定工具的超时秒数")
    void timeoutReachesTheDefinition() {
        SearchToolProperties properties = new SearchToolProperties();
        properties.setTimeout(Duration.ofSeconds(3));

        ToolDefinition<SearchToolExecutor> definition = new SearchToolAutoConfiguration()
                .searchToolDefinition(objectMapper, provider(registryOf(1)), properties);

        assertEquals(3L, definition.timeout());
    }

    @Test
    @DisplayName("亚秒超时收敛到 1 秒，而不是被当成「不超时」")
    void subSecondTimeoutDoesNotBecomeNoTimeout() {
        SearchToolProperties properties = new SearchToolProperties();
        properties.setTimeout(Duration.ofMillis(200));

        ToolDefinition<SearchToolExecutor> definition = new SearchToolAutoConfiguration()
                .searchToolDefinition(objectMapper, provider(registryOf(1)), properties);

        // 工具运行时把 timeout <= 0 读作「不设超时」，与「200 毫秒」的意图正好相反。
        assertEquals(1L, definition.timeout());
    }

    @Test
    @DisplayName("默认值：30 条 / 10 秒，与 SearchToolProperties 的字段默认一致")
    void defaultsMatchThePropertyFieldDefaults() {
        SearchToolProperties properties = new SearchToolProperties();

        ToolDefinition<SearchToolExecutor> definition = new SearchToolAutoConfiguration()
                .searchToolDefinition(objectMapper, provider(registryOf(40)), properties);

        assertEquals(30, properties.getMaxMatches());
        assertEquals(10L, definition.timeout());
        assertEquals(ConcurrentPolicy.READ_ONLY, definition.concurrentPolicy());
        assertEquals(SearchToolExecutor.NAME, definition.name());
    }

    // --- helpers ---------------------------------------------------------------------------

    private static ToolRegistry registryOf(int count) {
        List<ToolDefinition<?>> tools = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String name = "tool_" + i;
            tools.add(ToolDefinition.builder()
                    .id(name).name(name).maxOutput(100).timeout(5L)
                    .description("static " + name)
                    .concurrentPolicy(ConcurrentPolicy.READ_ONLY)
                    .executor(execution -> ToolExecuteResult.success("ok"))
                    .build());
        }
        ToolRegistry registry = new ToolRegistry(List.of());
        tools.forEach(tool -> registry.getTools().put(tool.name(), tool));
        return registry;
    }

    private static ObjectProvider<ToolRegistry> provider(ToolRegistry registry) {
        return new ObjectProvider<>() {
            @Override
            public ToolRegistry getObject() {
                return registry;
            }

            @Override
            public ToolRegistry getObject(Object... args) {
                return registry;
            }

            @Override
            public ToolRegistry getIfAvailable() {
                return registry;
            }

            @Override
            public ToolRegistry getIfUnique() {
                return registry;
            }
        };
    }

    private static ToolExecution execution(String args) {
        return ToolExecution.builder()
                .executionId("execution-1")
                .args(args)
                .mcpToolScope(McpToolScope.EMPTY)
                .build();
    }
}
