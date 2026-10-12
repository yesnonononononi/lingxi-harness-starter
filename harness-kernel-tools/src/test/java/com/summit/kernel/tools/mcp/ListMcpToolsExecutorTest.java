package com.summit.kernel.tools.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.summit.core.mcp.McpSession;
import com.summit.core.mcp.McpToolScope;
import com.summit.core.tool.ConcurrentPolicy;
import com.summit.core.tool.ToolDefinition;
import com.summit.core.tool.ToolExecuteResult;
import com.summit.core.tool.ToolExecution;
import com.summit.core.tool.ToolExecutor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工具列举的窗口分页回归。
 *
 * <p>一个服务可能带几十上百个工具，列举必须能一段段读完，而不是只给头一段就再也够不到
 * 后面的。四组断言缺一不可：</p>
 * <ol>
 *   <li><b>默认窗口。</b>不给区间时只读前 {@code defaultWindow} 个，而不是照单全收或读满上限。</li>
 *   <li><b>区间可达。</b>给了 {@code start}/{@code end} 就必须真的读到后半段 —— 只截头部的实现
 *       在这里失败，那正是「超过一段就只能盲搜」的原形。</li>
 *   <li><b>窗口有上限。</b>单次区间再宽也被收窄到 {@code maxWindow}，否则一次调用就能灌满上下文。</li>
 *   <li><b>越界不炸。</b>区间越界、倒置、参数非法都收敛成安全结果，不能让一次列举失败整轮。</li>
 * </ol>
 */
class ListMcpToolsExecutorTest {

    /** 与 {@code ListMcpToolsProperties} 的默认值一致。 */
    private static final int MAX_WINDOW = 50;
    private static final int DEFAULT_WINDOW = 20;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("不给区间时读前一段，total 报真实全量，hasMore 指出后面还有")
    void defaultWindowReadsTheHeadAndReportsMore() throws Exception {
        JsonNode body = listing(137, "{}");

        assertEquals(137, body.get("total").asInt(), "total 永远是服务真实全量，不受窗口影响");
        assertEquals(0, body.get("start").asInt());
        assertEquals(DEFAULT_WINDOW, body.get("end").asInt());
        assertEquals(DEFAULT_WINDOW, body.get("tools").size());
        assertTrue(body.get("hasMore").asBoolean(), "137 个工具只读了前 20，后面必然还有");
    }

    @Test
    @DisplayName("给了区间就读到后半段，而不是从头再截一次")
    void explicitRangeReadsPastTheFirstWindow() throws Exception {
        JsonNode body = listing(137, "{\"start\":20,\"end\":40}");

        assertEquals(137, body.get("total").asInt());
        assertEquals(20, body.get("start").asInt());
        assertEquals(40, body.get("end").asInt());
        assertEquals(List.of("tool_020", "tool_021"), names(body).subList(0, 2),
                "区间必须是 [start, end)，从 slice 起点开始");
        assertEquals(20, body.get("tools").size());
        assertTrue(body.get("hasMore").asBoolean());
    }

    @Test
    @DisplayName("只给 start 时按默认窗口向后推，不退回 0")
    void startAloneExtendsByTheDefaultWindow() throws Exception {
        JsonNode body = listing(137, "{\"start\":100}");

        assertEquals(100, body.get("start").asInt());
        assertEquals(120, body.get("end").asInt(), "end 缺省 = start + defaultWindow");
        assertEquals(List.of("tool_100"), names(body).subList(0, 1));
    }

    @Test
    @DisplayName("读到末尾时 hasMore 为假，且不越界")
    void lastWindowReportsNoMore() throws Exception {
        JsonNode body = listing(30, "{\"start\":20,\"end\":40}");

        assertEquals(30, body.get("total").asInt());
        assertEquals(20, body.get("start").asInt());
        assertEquals(30, body.get("end").asInt(), "end 被收窄到 total");
        assertEquals(10, body.get("tools").size());
        assertFalse(body.get("hasMore").asBoolean(), "已读到末尾");
    }

    @Test
    @DisplayName("区间宽于上限时收窄到上限，不能一次灌满上下文")
    void rangeWiderThanTheCapIsNarrowed() throws Exception {
        JsonNode body = listing(137, "{\"start\":0,\"end\":500}");

        assertEquals(0, body.get("start").asInt());
        assertEquals(MAX_WINDOW, body.get("end").asInt(), "end 被收窄到 start + maxWindow");
        assertEquals(MAX_WINDOW, body.get("tools").size());
    }

    @Test
    @DisplayName("start 越界收敛到末尾，返回空窗口而不是抛错")
    void startBeyondTheEndYieldsAnEmptyWindow() throws Exception {
        JsonNode body = listing(5, "{\"start\":999,\"end\":999}");

        assertEquals(5, body.get("total").asInt());
        assertEquals(5, body.get("start").asInt(), "start 被收窄到 total");
        assertEquals(5, body.get("end").asInt());
        assertEquals(0, body.get("tools").size());
        assertFalse(body.get("hasMore").asBoolean());
    }

    @Test
    @DisplayName("区间倒置收敛成空窗口，不回卷到列表尾部")
    void invertedRangeCollapsesRatherThanWrapping() throws Exception {
        JsonNode body = listing(137, "{\"start\":40,\"end\":10}");

        assertEquals(40, body.get("start").asInt());
        assertEquals(40, body.get("end").asInt(), "end < start 时收敛到 start");
        assertEquals(0, body.get("tools").size());
    }

    @Test
    @DisplayName("参数缺失或非法 JSON 都按默认窗口处理，不失败")
    void toleratesMissingOrMalformedArguments() throws Exception {
        for (String args : List.of("", "  ", "{}", "{\"start\":null}", "not json at all", "[1,2]")) {
            JsonNode body = listing(137, args);
            assertEquals(DEFAULT_WINDOW, body.get("tools").size(), "args=[" + args + "] 应按默认窗口处理");
        }
    }

    @Test
    @DisplayName("负数区间不会读出空指针：收敛到合法窗口")
    void negativeBoundsAreClamped() throws Exception {
        JsonNode body = listing(137, "{\"start\":-10,\"end\":-5}");

        assertEquals(0, body.get("start").asInt());
        assertEquals(0, body.get("end").asInt());
        assertEquals(0, body.get("tools").size());
    }

    @Test
    @DisplayName("窗口上限与默认窗口取自配置，改配置即改行为")
    void windowsComeFromConfiguration() throws Exception {
        ListMcpToolsExecutor executor = new ListMcpToolsExecutor(objectMapper, 4, 2);

        JsonNode body = objectMapper.readTree(
                executor.execute(execution("{}", scopeOf(manyTools(20)))).getToolOutput());

        assertEquals(20, body.get("total").asInt());
        assertEquals(2, body.get("end").asInt(), "默认窗口取配置值");
    }

    @Test
    @DisplayName("默认窗口大于上限时收窄到上限，不产生必然被截的配置")
    void defaultWindowIsCappedByMaxWindow() throws Exception {
        ListMcpToolsExecutor executor = new ListMcpToolsExecutor(objectMapper, 3, 99);

        JsonNode body = objectMapper.readTree(
                executor.execute(execution("{}", scopeOf(manyTools(20)))).getToolOutput());

        assertEquals(3, body.get("tools").size(), "默认窗口不得超过单次上限");
    }

    @Test
    @DisplayName("点名服务时窗口只在该服务内展开")
    void windowAppliesWithinTheNamedServer() throws Exception {
        McpToolScope scope = McpToolScope.of(List.of(
                sessionOf("alpha", manyTools("alpha", 10)),
                sessionOf("beta", manyTools("beta", 30))));

        JsonNode body = objectMapper.readTree(new ListMcpToolsExecutor(objectMapper, MAX_WINDOW, DEFAULT_WINDOW)
                .execute(execution("{\"mcpName\":\"beta\",\"start\":0,\"end\":5}", scope)).getToolOutput());

        assertEquals(30, body.get("total").asInt(), "total 是 beta 的工具数，不是两个服务之和");
        // 窗口按名字字典序展开：beta_10 排在 beta_2 前面，这正是 toolsOfServer 的排序契约。
        assertEquals(List.of("beta_0", "beta_1", "beta_10", "beta_11", "beta_12"), names(body));
    }

    @Test
    @DisplayName("服务名给错时报出全部服务名，便于下一步纠正")
    void unknownServerNamesTheAvailableOnes() throws Exception {
        McpToolScope scope = McpToolScope.of(List.of(sessionOf("alpha", manyTools("alpha", 3))));

        JsonNode body = objectMapper.readTree(new ListMcpToolsExecutor(objectMapper, MAX_WINDOW, DEFAULT_WINDOW)
                .execute(execution("{\"mcpName\":\"nope\"}", scope)).getToolOutput());

        assertEquals(0, body.get("total").asInt());
        assertEquals("nope", body.get("mcpName").asText());
        assertEquals(List.of("alpha"), textList(body, "servers"));
    }

    // --- helpers ---------------------------------------------------------------------------

    private JsonNode listing(int toolCount, String args) throws Exception {
        return objectMapper.readTree(
                executor().execute(execution(args, scopeOf(manyTools(toolCount)))).getToolOutput());
    }

    private ListMcpToolsExecutor executor() {
        return new ListMcpToolsExecutor(objectMapper, MAX_WINDOW, DEFAULT_WINDOW);
    }

    private static List<ToolDefinition<?>> manyTools(int count) {
        List<ToolDefinition<?>> tools = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            // 零填充，避免 tool_10 排在 tool_2 前面打乱名字序断言。
            tools.add(tool(String.format("tool_%03d", i)));
        }
        return tools;
    }

    private static List<ToolDefinition<? extends ToolExecutor>> manyTools(String prefix, int count) {
        List<ToolDefinition<? extends ToolExecutor>> tools = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            tools.add(tool(prefix + "_" + i));
        }
        return tools;
    }

    private static ToolDefinition<?> tool(String name) {
        return ToolDefinition.builder()
                .id(name).name(name).maxOutput(1000).timeout(10L)
                .description("remote " + name)
                .concurrentPolicy(ConcurrentPolicy.READ_ONLY)
                .executor(execution -> ToolExecuteResult.success("ok"))
                .build();
    }

    private McpToolScope scopeOf(List<ToolDefinition<?>> tools) {
        return McpToolScope.of(List.of(sessionOf("test-server", List.copyOf(tools))));
    }

    private static McpSession sessionOf(String name, List<ToolDefinition<? extends ToolExecutor>> tools) {
        return new McpSession() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public List<ToolDefinition<? extends ToolExecutor>> tools() {
                return tools;
            }

            @Override
            public void close() {
            }
        };
    }

    private static List<String> names(JsonNode body) {
        List<String> names = new ArrayList<>();
        body.get("tools").forEach(item -> names.add(item.get("name").asText()));
        return names;
    }

    private static List<String> textList(JsonNode body, String field) {
        List<String> values = new ArrayList<>();
        body.get(field).forEach(item -> values.add(item.asText()));
        return values;
    }

    private ToolExecution execution(String args, McpToolScope scope) {
        return ToolExecution.builder()
                .executionId("execution-1")
                .args(args)
                .mcpToolScope(scope)
                .build();
    }
}
