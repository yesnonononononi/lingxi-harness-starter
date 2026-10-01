package com.summit.runtime.conversation;

import com.summit.core.agent.AgentRequest;
import com.summit.core.agent.Execution;
import com.summit.core.compact.ContextAttachmentProvider;
import com.summit.core.conf.McpConfig;
import com.summit.core.conf.McpTransport;
import com.summit.core.conversation.message.Message;
import com.summit.core.conversation.message.SystemMessageEntity;
import com.summit.core.conversation.message.UserMessageEntity;
import com.summit.core.mcp.McpSession;
import com.summit.core.mcp.McpToolScope;
import com.summit.core.runtime.RuntimeEnvironment;
import com.summit.core.runtime.workspace.OsType;
import com.summit.core.runtime.workspace.ShellType;
import com.summit.core.runtime.workspace.Workspace;
import com.summit.core.tool.ConcurrentPolicy;
import com.summit.core.tool.ToolDefinition;
import com.summit.core.tool.ToolExecuteResult;
import com.summit.core.tool.ToolExecutor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The leading system message of one execution, asserted as rendered text.
 *
 * <p>This is the prompt a model actually receives on its first round, so it is verified end to end
 * rather than section by section: the assembler's output, the section order and the progressive
 * disclosure of remote tools are all visible in one string.</p>
 */
class DefaultConversationManagerSystemPromptTest {

    private final DefaultConversationManager conversations =
            new DefaultConversationManager(ContextAttachmentProvider.NONE);

    @Test
    @DisplayName("根请求（无 task、有 MCP）：环境 → 角色 → 远端工具摘要")
    void rootRequestWithRemoteTools() {
        String prompt = startConversation(
                AgentRequest.builder()
                        .messages(List.of(UserMessageEntity.from("帮我看看这个仓库")))
                        .systemPrompt("You are LingXi, a coding agent. Reply in the user's language.")
                        .toolList(List.of("read_file", "edit_file", "search_tool"))
                        .mcpConfig(mcpConfig())
                        .build(),
                sandbox(),
                scopeOf(githubTools()));

        print(prompt);

        assertEquals("""
                ## Execution environment
                - Commands run in the assigned workspace environment, not necessarily on the host machine.
                - Reported OS: LINUX; shell: BASH; charset: UTF-8.
                - Working directory: /workspace
                - This workspace is isolated; host tools and files are available only when explicitly exposed.
                - Environment variables exposed to this workspace:
                  - HOME=/root
                  - LANG=en_US.UTF-8
                  - MCP_APIKEY=<redacted>
                  - PATH=/usr/local/bin:/usr/bin

                ## Business Prompt
                You are LingXi, a coding agent. Reply in the user's language.

                ## MCP Tools
                Remote tools are hosted by MCP servers; the counts below are how many each one contributed.
                Their parameter schemas are deliberately left out of this prompt. Call `list_mcp_tools` with
                a server name to list that server's tools (name and description), or `search_tool` with a
                keyword to get one tool's schema directly. A tool found either way becomes callable from your
                next turn on.

                - github - GitHub remote tools - 2 tools""", prompt);
    }

    @Test
    @DisplayName("子代理请求（有 task）：任务清单排在最后，紧邻用户回合")
    void delegatedRequestEndsWithTheTaskManifest() {
        String prompt = startConversation(
                AgentRequest.builder()
                        .messages(List.of(UserMessageEntity.from("请只回复一个词：ok")))
                        .systemPrompt("你是一名后端工程师。")
                        .task(List.of("请只回复一个词：ok"))
                        .toolList(List.of("read_file"))
                        .build(),
                sandbox(),
                McpToolScope.EMPTY);

        print(prompt);

        assertTrue(prompt.endsWith("## Task Manifest\n请只回复一个词：ok"),
                "任务清单必须是最后一段：它离用户回合最近，注意力权重最高");
        assertFalse(prompt.contains("## MCP Tools"), "没有远端工具就不该出现空段落");
    }

    @Test
    @DisplayName("裸模型请求：没有角色提示词也没有 task 时不留空标题")
    void bareRequestKeepsNoEmptySections() {
        String prompt = startConversation(
                AgentRequest.builder()
                        .messages(List.of(UserMessageEntity.from("hi")))
                        .build(),
                sandbox(),
                McpToolScope.EMPTY);

        print(prompt);

        assertFalse(prompt.contains("## Business Prompt"), "空角色提示词不该留下标题");
        assertFalse(prompt.contains("## Task Manifest"), "空任务清单不该留下标题");
        assertFalse(prompt.contains("## MCP Tools"), "空远端工具清单不该留下标题");
        assertFalse(prompt.contains("## System Prompt"),
                "框架不再有自己的提示词段落：提示词只有一个来源");
        assertTrue(prompt.startsWith("## Execution environment"), "只剩环境段");
    }

    /**
     * The assembler used to append to one builder and return another, so the environment section
     * never reached the model. It is the first thing a model needs to know, so it is pinned.
     */
    @Test
    @DisplayName("执行环境段必须真的出现在提示词里（此前 append 写到了被丢弃的实例上）")
    void executionEnvironmentIsActuallyPresent() {
        String prompt = startConversation(
                AgentRequest.builder().messages(List.of(UserMessageEntity.from("hi"))).build(),
                sandbox(), McpToolScope.EMPTY);

        assertTrue(prompt.startsWith("## Execution environment"), "环境段是首段，且不带前导空行");
        assertTrue(prompt.contains("- Reported OS: LINUX; shell: BASH; charset: UTF-8."));
        assertTrue(prompt.contains("- Working directory: /workspace"));
    }

    /**
     * The prompt is the request's, verbatim. Nothing is appended to it and nothing stands in for it,
     * so an application can predict exactly what its agents are told.
     */
    @Test
    @DisplayName("提示词逐字来自 AgentRequest.systemPrompt，框架不加料也不补默认值")
    void promptComesFromTheRequestVerbatim() {
        String prompt = startConversation(
                AgentRequest.builder()
                        .messages(List.of(UserMessageEntity.from("hi")))
                        .systemPrompt("  你是后端工程师，只回答结论。  ")
                        .build(),
                sandbox(), McpToolScope.EMPTY);

        assertTrue(prompt.contains("## Business Prompt\n你是后端工程师，只回答结论。"),
                "首尾空白被收掉，正文一字不改");
        assertFalse(prompt.contains("%s"), "框架不引入任何占位符");
    }

    /**
     * The prompt publishes the first level of progressive disclosure only — one line per server,
     * carrying its description and its tool count. Tool names wait for {@code list_mcp_tools} and
     * schemas for {@code search_tool}, so a summary in the prompt is never a declaration.
     */
    @Test
    @DisplayName("提示词只到服务级（名字+描述+工具数），工具名与 schema 都不进")
    void remoteServersAppearAsResumesOnly() {
        String prompt = startConversation(
                AgentRequest.builder().messages(List.of(UserMessageEntity.from("hi"))).build(),
                sandbox(), scopeOf(githubTools()));

        assertTrue(prompt.contains("- github - GitHub remote tools - 2 tools"),
                "一行一个服务：名字 + 描述 + 工具数");
        assertFalse(prompt.contains("mcp_github_get_me"), "工具级留给 list_mcp_tools，不进提示词");
        assertFalse(prompt.contains("create_issue"), "工具名不进提示词");
        assertFalse(prompt.contains("owner"), "参数名只存在于 schema，不该出现在提示词里");
        assertFalse(prompt.contains("properties"), "参数 schema 不该出现在提示词里");
    }

    // --- helpers ---------------------------------------------------------------------------

    private String startConversation(AgentRequest request, Workspace workspace, McpToolScope scope) {
        Execution execution = Execution.builder()
                .id("e-1").agentId("a")
                .messages(new ArrayList<>(List.of(UserMessageEntity.from("hi"))))
                .agentRequest(request)
                .build();

        conversations.startConversation(execution, workspace, scope);

        List<Message> messages = execution.getMessages();
        SystemMessageEntity leading = assertInstanceOf(SystemMessageEntity.class, messages.getFirst(),
                "首条消息必须是系统提示词");
        assertEquals(2, messages.size(), "系统提示词 + 用户消息，没有多余条目");
        assertEquals("hi", messages.get(1).text(), "用户消息紧随其后，不被挤走");
        return leading.getText();
    }

    private static void print(String prompt) {
        System.out.println("========== INITIAL SYSTEM PROMPT ==========");
        System.out.println(prompt.replace(" ", "·").replace("\n", "\n| "));
        System.out.println("========== END ==========");
    }

    private static Workspace sandbox() {
        return new Workspace() {
            @Override
            public String id() {
                return "workspace-1";
            }

            @Override
            public RuntimeEnvironment runtimeEnvironment() {
                Map<String, String> envs = new LinkedHashMap<>();
                envs.put("PATH", "/usr/local/bin:/usr/bin");
                envs.put("HOME", "/root");
                envs.put("LANG", "en_US.UTF-8");
                envs.put("MCP_APIKEY", "sk-should-never-reach-the-model");
                return RuntimeEnvironment.builder()
                        .osType(OsType.LINUX)
                        .shellType(ShellType.BASH)
                        .charset(StandardCharsets.UTF_8)
                        .isolated(true)
                        .envs(envs)
                        .build();
            }

            @Override
            public String workDir() {
                return "/workspace";
            }

            @Override
            public Path resolve(String path) {
                return Path.of("/workspace", path);
            }
        };
    }

    private static List<ToolDefinition<?>> githubTools() {
        return List.of(
                remoteTool("mcp_github_get_me", "Get the authenticated GitHub user"),
                // 多行描述：摘要里必须压成一行，否则列表会被撑断
                remoteTool("mcp_github_create_issue",
                        "Open an issue in a GitHub repository\n      from a one-line summary"));
    }

    private static ToolDefinition<?> remoteTool(String name, String description) {
        return ToolDefinition.builder()
                .id(name).name(name).maxOutput(2_000).timeout(20L)
                .description(description)
                .parametersJsonSchema("{\"type\":\"object\",\"properties\":{\"owner\":{\"type\":\"string\"}}}")
                .concurrentPolicy(ConcurrentPolicy.READ_ONLY)
                .executor(execution -> ToolExecuteResult.success("ok"))
                .build();
    }

    private static McpToolScope scopeOf(List<ToolDefinition<?>> tools) {
        List<ToolDefinition<? extends ToolExecutor>> scoped = new ArrayList<>(tools);
        return McpToolScope.of(List.of(new McpSession() {
            @Override
            public String name() {
                return "github";
            }

            @Override
            public String description() {
                return "GitHub remote tools";
            }

            @Override
            public List<ToolDefinition<? extends ToolExecutor>> tools() {
                return scoped;
            }

            @Override
            public void close() {
            }
        }));
    }

    private static McpConfig mcpConfig() {
        McpConfig config = new McpConfig();
        config.setMcp(List.of(new McpConfig.MCP("github", "GitHub remote tools", McpTransport.STREAMABLE_HTTP,
                new McpConfig.StreamableHttp("https://example.invalid/mcp", Map.of(), null, null),
                1000)));
        return config;
    }
}
