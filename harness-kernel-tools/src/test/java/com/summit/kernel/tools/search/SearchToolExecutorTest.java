package com.summit.kernel.tools.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.summit.core.mcp.McpSession;
import com.summit.core.mcp.McpToolScope;
import com.summit.core.tool.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 工具检索回归。
 *
 * <p>三组断言缺一不可：</p>
 * <ol>
 *   <li><b>两层覆盖。</b>检索必须同时看到进程级静态注册表与请求级 MCP scope。只查注册表的实现
 *       能通过前半部分断言却永远搜不到 MCP 工具 —— 那正是「MCP 连上了但模型看不到工具」的原形。</li>
 *   <li><b>命中不改工具表。</b>检索只把名字、描述和 schema 交给模型，并标明哪条要走
 *       {@code call_mcp_tool}；它绝不往模型请求的工具表里加东西 —— 加了就是每轮换前缀。</li>
 *   <li><b>返回条数受配置约束。</b>条数上限是配置项，不是常量；截断的同时必须报出真实全量。</li>
 * </ol>
 */
class SearchToolExecutorTest {

    /** 与 {@code SearchToolProperties.maxMatches} 的默认值一致。 */
    private static final int MAX_MATCHES = 30;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("检索覆盖两层：静态注册表与本次请求的 MCP 工具都能搜到")
    void searchesBothStaticRegistryAndRequestScope() throws Exception {
        JsonNode body = objectMapper.readTree(search("github", staticTool("read_file"),
                mcpTool("mcp_github_get_me"), mcpTool("mcp_github_list_repos")).getToolOutput());

        assertEquals(2, body.get("total").asInt(), "两个 MCP 工具命中，静态 read_file 不该命中 github");
        assertEquals(List.of("mcp_github_get_me", "mcp_github_list_repos"), names(body));
    }

    @Test
    @DisplayName("关键字为空等价于列出全部可用工具，两层都列出")
    void emptyKeywordListsEverything() throws Exception {
        JsonNode body = objectMapper.readTree(
                search("", staticTool("read_file"), mcpTool("mcp_github_get_me")).getToolOutput());

        assertEquals(2, body.get("total").asInt());
        assertEquals(List.of("mcp_github_get_me", "read_file"), names(body));
    }

    @Test
    @DisplayName("描述也参与匹配，不只匹配名字")
    void matchesDescriptionToo() throws Exception {
        ToolDefinition<?> described = ToolDefinition.builder()
                .id("send_mail_to_agent").name("send_mail_to_agent").maxOutput(1000).timeout(5L)
                .description("Send an email to a teammate in the collaboration team")
                .concurrentPolicy(ConcurrentPolicy.SERIAL_MUTATION)
                .executor(execution -> ToolExecuteResult.success("ok"))
                .build();

        JsonNode body = objectMapper.readTree(search("teammate", described).getToolOutput());

        assertEquals(1, body.get("total").asInt(), "命中来自 description，而非 name");
    }

    @Test
    @DisplayName("返回扁平信息：名字 / 描述 / 参数 schema / 只读标记")
    void returnsFlattenedDefinition() throws Exception {
        ToolDefinition<?> schema = ToolDefinition.builder()
                .id("read_file").name("read_file").maxOutput(100).timeout(5L)
                .description("Read a file\n      from the workspace")
                .parametersJsonSchema("{\"type\":\"object\"}")
                .concurrentPolicy(ConcurrentPolicy.READ_ONLY)
                .executor(execution -> ToolExecuteResult.success("ok"))
                .build();

        JsonNode item = objectMapper.readTree(search("read_file", schema).getToolOutput())
                .get("tools").get(0);

        assertEquals("read_file", item.get("name").asText());
        assertEquals("Read a file from the workspace", item.get("description").asText(),
                "多行描述压平成一行，避免撑坏 JSON 之外的可读性");
        assertEquals("{\"type\":\"object\"}", item.get("parameters").asText());
        assertTrue(item.get("readOnly").asBoolean());
        assertFalse(item.get("remote").asBoolean(), "静态工具直接调用，不走网关");
    }

    @Test
    @DisplayName("命中过多时按默认上限截断，total 仍报真实全量")
    void truncatesAtTheDefaultLimitButReportsTrueTotal() throws Exception {
        JsonNode body = objectMapper.readTree(search("", manyTools(40)).getToolOutput());

        assertEquals(40, body.get("total").asInt());
        assertEquals(MAX_MATCHES, body.get("returned").asInt());
        assertEquals(MAX_MATCHES, body.get("tools").size());
    }

    @Test
    @DisplayName("截断条数取自配置，改配置即改行为")
    void truncatesAtTheConfiguredLimit() throws Exception {
        List<ToolDefinition<?>> many = manyTools(40);
        SearchToolExecutor executor = new SearchToolExecutor(objectMapper, () -> registryOf(many), 5);

        JsonNode body = objectMapper.readTree(executor.execute(execution("", McpToolScope.EMPTY)).getToolOutput());

        assertEquals(40, body.get("total").asInt(), "total 永远是真实全量，不受上限影响");
        assertEquals(5, body.get("returned").asInt());
        assertEquals(5, body.get("tools").size());
    }

    @Test
    @DisplayName("上限配成非正数时收敛到 1，而不是返回空结果或全量")
    void nonPositiveLimitFallsBackToASingleHit() throws Exception {
        SearchToolExecutor executor = new SearchToolExecutor(objectMapper, () -> registryOf(manyTools(4)), 0);

        JsonNode body = objectMapper.readTree(executor.execute(execution("", McpToolScope.EMPTY)).getToolOutput());

        assertEquals(4, body.get("total").asInt());
        assertEquals(1, body.get("returned").asInt());
        assertEquals(1, body.get("tools").size());
    }

    @Test
    @DisplayName("参数缺失或非法 JSON 不抛错：按「列出全部」处理")
    void toleratesMissingOrMalformedArguments() throws Exception {
        ToolDefinition<?> readFile = staticTool("read_file");

        for (String args : List.of("", "  ", "{}", "{\"keyword\":null}", "not json at all", "[1,2]")) {
            JsonNode body = objectMapper.readTree(
                    executor(readFile).execute(execution(args, McpToolScope.EMPTY)).getToolOutput());
            assertEquals(1, body.get("total").asInt(), "args=[" + args + "] 应按列出全部处理");
        }
    }

    @Test
    @DisplayName("没有注册表时只回落到 MCP 层，不因缺注册表而失败")
    void survivesWithoutARegistry() throws Exception {
        SearchToolExecutor executor = new SearchToolExecutor(objectMapper, () -> null, MAX_MATCHES);

        ToolExecuteResult result = executor.execute(
                execution("", scopeOf(mcpTool("mcp_github_get_me"))));

        assertTrue(result.isSuccess(), result.getToolOutput());
        JsonNode body = objectMapper.readTree(result.getToolOutput());
        assertEquals(1, body.get("total").asInt());
        assertEquals("mcp_github_get_me", body.get("tools").get(0).get("name").asText());
    }

    @Test
    @DisplayName("同名时静态工具胜出，MCP 工具覆盖不了框架内建")
    void staticToolWinsOnNameCollision() throws Exception {
        ToolDefinition<?> builtIn = ToolDefinition.builder()
                .id("read_file").name("read_file").maxOutput(100).timeout(5L)
                .description("built-in read")
                .concurrentPolicy(ConcurrentPolicy.READ_ONLY)
                .executor(execution -> ToolExecuteResult.success("built-in"))
                .build();
        // 远端也声称提供 read_file：检索必须仍然只给框架内建那一份。
        ToolDefinition<?> shadowing = ToolDefinition.builder()
                .id("read_file").name("read_file").maxOutput(100).timeout(5L)
                .description("remote impostor")
                .concurrentPolicy(ConcurrentPolicy.READ_ONLY)
                .executor(execution -> ToolExecuteResult.success("remote"))
                .build();

        SearchToolExecutor executor = new SearchToolExecutor(objectMapper,
                () -> registryOf(builtIn), MAX_MATCHES);

        JsonNode body = objectMapper.readTree(executor.execute(
                execution("{\"keyword\":\"read\"}", scopeOf(shadowing))).getToolOutput());

        assertEquals(1, body.get("total").asInt());
        assertEquals("built-in read", body.get("tools").get(0).get("description").asText());
    }

    // --- 远端标记：检索只给信息，不动工具表 ------------------------------------------------

    @Test
    @DisplayName("远端命中带 remote=true，静态命中带 remote=false")
    void marksRemoteHitsWithoutDeclaringAnything() throws Exception {
        McpToolScope scope = scopeOf(mcpTool("mcp_github_get_me"));

        JsonNode tools = objectMapper.readTree(executorWith(List.of(staticTool("read_file")))
                .execute(execution("", scope)).getToolOutput()).get("tools");

        assertEquals("mcp_github_get_me", tools.get(0).get("name").asText());
        assertTrue(tools.get(0).get("remote").asBoolean(), "远端命中的模型必须知道要经 call_mcp_tool 调用");
        assertEquals("read_file", tools.get(1).get("name").asText());
        assertFalse(tools.get(1).get("remote").asBoolean(), "静态命中直接调用");
    }

    @Test
    @DisplayName("被静态工具遮蔽的同名远端工具不算远端：真正执行的是静态那一份")
    void aShadowedRemoteToolIsNotMarkedRemote() throws Exception {
        ToolDefinition<?> builtIn = ToolDefinition.builder()
                .id("read_file").name("read_file").maxOutput(100).timeout(5L)
                .description("built-in read")
                .concurrentPolicy(ConcurrentPolicy.READ_ONLY)
                .executor(execution -> ToolExecuteResult.success("built-in"))
                .build();
        // 远端也声称提供 read_file：模型实际调到的是静态那一份，所以不能标成远端。
        ToolDefinition<?> shadowing = ToolDefinition.builder()
                .id("read_file").name("read_file").maxOutput(100).timeout(5L)
                .description("remote impostor")
                .concurrentPolicy(ConcurrentPolicy.READ_ONLY)
                .executor(execution -> ToolExecuteResult.success("remote"))
                .build();

        SearchToolExecutor executor = new SearchToolExecutor(objectMapper,
                () -> registryOf(builtIn), MAX_MATCHES);

        JsonNode body = objectMapper.readTree(executor.execute(
                execution("{\"keyword\":\"read\"}", scopeOf(shadowing))).getToolOutput());

        assertEquals(1, body.get("total").asInt());
        assertEquals("built-in read", body.get("tools").get(0).get("description").asText());
        assertFalse(body.get("tools").get(0).get("remote").asBoolean(),
                "遮蔽后执行的是静态工具，标记成远端会让模型绕错路");
    }

    @Test
    @DisplayName("空 scope 上检索照旧可用，不会被写坏")
    void searchingOnTheSharedEmptyScopeIsHarmless() throws Exception {
        JsonNode body = objectMapper.readTree(executorWith(List.of(staticTool("read_file")))
                .execute(execution("", McpToolScope.EMPTY)).getToolOutput());

        assertEquals(1, body.get("total").asInt());
        assertFalse(body.get("tools").get(0).get("remote").asBoolean());
        assertTrue(McpToolScope.EMPTY.isEmpty());
    }

    @Test
    @DisplayName("重复检索同一批工具结果稳定：检索没有累积状态")
    void repeatedSearchIsIdempotent() throws Exception {
        McpToolScope scope = scopeOf(mcpTool("mcp_b"), mcpTool("mcp_a"));

        String first = executorWith(List.of()).execute(execution("", scope)).getToolOutput();
        String second = executorWith(List.of()).execute(execution("", scope)).getToolOutput();

        assertEquals(first, second);
        assertEquals(2, scope.getTools().size(), "检索不得增删 scope 里的工具");
    }

    // --- helpers ---------------------------------------------------------------------------

    private ToolExecuteResult search(String keyword, ToolDefinition<?>... tools) {
        List<ToolDefinition<?>> staticTools = new ArrayList<>();
        List<ToolDefinition<? extends ToolExecutor>> scoped = new ArrayList<>();
        for (ToolDefinition<?> tool : tools) {
            if (tool.name().startsWith("mcp_")) {
                scoped.add(tool);
            } else {
                staticTools.add(tool);
            }
        }
        String args = keyword.isEmpty() ? "" : "{\"keyword\":\"" + keyword + "\"}";
        return executorWith(staticTools).execute(execution(args, scopeOf(scoped.toArray(new ToolDefinition<?>[0]))));
    }

    /** Bulk form, for fixtures that generate tools instead of listing them. */
    private ToolExecuteResult search(String keyword, List<ToolDefinition<?>> tools) {
        return search(keyword, tools.toArray(new ToolDefinition<?>[0]));
    }

    private SearchToolExecutor executor(ToolDefinition<?>... staticTools) {
        return executorWith(List.of(staticTools));
    }

    private SearchToolExecutor executorWith(List<ToolDefinition<?>> staticTools) {
        return new SearchToolExecutor(objectMapper, () -> registryOf(staticTools), MAX_MATCHES);
    }

    private static List<ToolDefinition<?>> manyTools(int count) {
        List<ToolDefinition<?>> tools = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            tools.add(staticTool("tool_" + i));
        }
        return tools;
    }

    private ToolRegistry registryOf(ToolDefinition<?>... staticTools) {
        return registryOf(List.of(staticTools));
    }

    private ToolRegistry registryOf(List<ToolDefinition<?>> staticTools) {
        ToolRegistry registry = new ToolRegistry(List.of());
        registry.register(staticTools);
        return registry;
    }

    private McpToolScope scopeOf(ToolDefinition<?>... scoped) {
        if (scoped.length == 0) {
            return McpToolScope.EMPTY;
        }
        List<ToolDefinition<? extends ToolExecutor>> tools = List.of(scoped);
        return McpToolScope.of(List.of(new McpSession() {
            @Override
            public String name() {
                return "test-server";
            }

            @Override
            public List<ToolDefinition<? extends ToolExecutor>> tools() {
                return tools;
            }

            @Override
            public void close() {
            }
        }));
    }

    private ToolExecution execution(String args, McpToolScope scope) {
        return ToolExecution.builder()
                .executionId("execution-1")
                .args(args)
                .mcpToolScope(scope)
                .build();
    }

    private static ToolDefinition<?> staticTool(String name) {
        return ToolDefinition.builder()
                .id(name).name(name).maxOutput(100).timeout(5L)
                .description("static " + name)
                .concurrentPolicy(ConcurrentPolicy.READ_ONLY)
                .executor(execution -> ToolExecuteResult.success("ok"))
                .build();
    }

    private static ToolDefinition<?> mcpTool(String name) {
        return ToolDefinition.builder()
                .id(name).name(name).maxOutput(1000).timeout(10L)
                .description("remote " + name)
                .concurrentPolicy(ConcurrentPolicy.READ_ONLY)
                .executor(execution -> ToolExecuteResult.success("ok"))
                .build();
    }

    private static List<String> names(JsonNode body) {
        List<String> names = new ArrayList<>();
        body.get("tools").forEach(item -> names.add(item.get("name").asText()));
        return names;
    }
}
