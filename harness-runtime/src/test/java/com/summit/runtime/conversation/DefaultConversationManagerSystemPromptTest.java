package com.summit.runtime.conversation;

import com.summit.core.agent.AgentRequest;
import com.summit.core.agent.ContextSpec;
import com.summit.core.agent.Execution;
import com.summit.core.compact.ContextAttachmentProvider;
import com.summit.core.conf.SensitiveDataSpec;
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

import static org.junit.jupiter.api.Assertions.*;

/**
 * The messages one execution sends on its first round, asserted as rendered text.
 *
 * <p>There are two halves and they have different owners. The leading system message is the part
 * that must never change once it is cached — environment and the application's own prompt. The
 * dynamic context is everything that moves between rounds — remote tools, skills, memory, task —
 * and it rides as a user message <em>before</em> the user turn, so the cached prefix survives and
 * the model still reads the question last.</p>
 */
class DefaultConversationManagerSystemPromptTest {

    private final DefaultConversationManager conversations =
            new DefaultConversationManager(ContextAttachmentProvider.NONE);

    @Test
    void memoryIsRenderedOnceBeforeTheTaskAndBlankEntriesAreOmitted() {
        var request = AgentRequest.builder().contextSpec(ContextSpec.builder()
                        .systemPrompt("role").memory(java.util.Arrays.asList("first memory", null, "  ", "second memory"))
                        .build()).task(List.of("final task")).build();

        Rendered prompt = startConversation(request, sandbox(), McpToolScope.EMPTY);

        assertTrue(prompt.dynamic().contains("## Memory Prompt\nfirst memory\nsecond memory"),
                "memory keeps its own line structure: " + prompt.dynamic());
        assertEquals(1, prompt.dynamic().split("## Memory Prompt", -1).length - 1);
        assertTrue(prompt.dynamic().endsWith("## Task Manifest\nfinal task"),
                "the task manifest stays the last section of the dynamic block");
        assertFalse(prompt.system().contains("## Memory Prompt"),
                "memory changes between rounds, so it must not sit in the cached system prompt");
    }

    @Test
    void nullEmptyAndBlankMemoryDoNotCreateAnEmptySection() {
        for (List<String> memory : java.util.Arrays.<List<String>>asList(
                null, List.of(), java.util.Arrays.asList(null, "", "  "))) {
            var request = AgentRequest.builder().contextSpec(ContextSpec.builder().memory(memory).build()).build();
            Rendered prompt = startConversation(request, sandbox(), McpToolScope.EMPTY);
            assertFalse(prompt.system().contains("## Memory Prompt"));
            assertFalse(prompt.dynamic().contains("## Memory Prompt"));
            assertEquals("", prompt.dynamic(),
                    "a request with no dynamic section must not get an empty user message injected");
        }
    }

    @Test
    @DisplayName("根请求（无 task、有 MCP）：系统提示词只有环境与角色，远端工具摘要走动态上下文")
    void rootRequestWithRemoteTools() {
        Rendered prompt = startConversation(
                AgentRequest.builder()
                        .contextSpec(ContextSpec.builder()
                                .context(List.of(UserMessageEntity.from("帮我看看这个仓库")))
                                .systemPrompt("You are LingXi, a coding agent. Reply in the user's language.")
                                .build())
                        .toolList(List.of("read_file", "edit_file", "search_tool"))
                        .mcpConfigSpec(SensitiveDataSpec.builder().id(99L).version(3).build())
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
                You are LingXi, a coding agent. Reply in the user's language.""", prompt.system(),
                "the cacheable half holds the environment and the application's own prompt, nothing else");

        assertTrue(prompt.dynamic().startsWith("<context>\n\n## MCP Tools\n"), prompt.dynamic());
        assertTrue(prompt.dynamic().contains("`hasMore`."), "the disclosure instructions survive");
        assertTrue(prompt.dynamic().endsWith("- github - GitHub remote tools - 2 tools"), prompt.dynamic());
    }

    @Test
    @DisplayName("子代理请求（有 task）：任务清单排在动态上下文最后，紧邻用户回合")
    void delegatedRequestEndsWithTheTaskManifest() {
        Rendered prompt = startConversation(
                AgentRequest.builder()
                        .contextSpec(ContextSpec.builder()
                                .context(List.of(UserMessageEntity.from("请只回复一个词：ok")))
                                .systemPrompt("你是一名后端工程师。")
                                .build())
                        .task(List.of("请只回复一个词：ok"))
                        .toolList(List.of("read_file"))
                        .build(),
                sandbox(),
                McpToolScope.EMPTY);

        print(prompt);

        assertTrue(prompt.dynamic().endsWith("## Task Manifest\n请只回复一个词：ok"),
                "任务清单必须是动态上下文的最后一段：它离用户回合最近，注意力权重最高");
        assertFalse(prompt.dynamic().contains("## MCP Tools"), "没有远端工具就不该出现空段落");
    }

    @Test
    @DisplayName("裸模型请求：没有角色提示词也没有 task 时不留空标题")
    void bareRequestKeepsNoEmptySections() {
        Rendered prompt = startConversation(
                AgentRequest.builder().contextSpec(ContextSpec.builder().context(List.of(UserMessageEntity.from("hi"))).build())
                        .build(),
                sandbox(),
                McpToolScope.EMPTY);

        print(prompt);

        assertFalse(prompt.system().contains("## Business Prompt"), "空角色提示词不该留下标题");
        assertFalse(prompt.system().contains("## Task Manifest"), "空任务清单不该留下标题");
        assertFalse(prompt.system().contains("## MCP Tools"), "空远端工具清单不该留下标题");
        assertFalse(prompt.system().contains("## System Prompt"),
                "框架不再有自己的提示词段落：提示词只有一个来源");
        assertTrue(prompt.system().startsWith("## Execution environment"), "只剩环境段");
        assertEquals("", prompt.dynamic(), "没有动态内容就只有两条消息");
    }

    /**
     * The assembler used to append to one builder and return another, so the environment section
     * never reached the model. It is the first thing a model needs to know, so it is pinned.
     */
    @Test
    @DisplayName("执行环境段必须真的出现在提示词里（此前 append 写到了被丢弃的实例上）")
    void executionEnvironmentIsActuallyPresent() {
        Rendered prompt = startConversation(
                AgentRequest.builder().contextSpec(ContextSpec.builder().context(List.of(UserMessageEntity.from("hi"))).build()).build(),
                sandbox(), McpToolScope.EMPTY);

        assertTrue(prompt.system().startsWith("## Execution environment"), "环境段是首段，且不带前导空行");
        assertTrue(prompt.system().contains("- Reported OS: LINUX; shell: BASH; charset: UTF-8."));
        assertTrue(prompt.system().contains("- Working directory: /workspace"));
    }

    /**
     * The prompt is the request's, verbatim. Nothing is appended to it and nothing stands in for it,
     * so an application can predict exactly what its agents are told.
     */
    @Test
    @DisplayName("提示词逐字来自 AgentRequest.contextSpec.systemPrompt，框架不加料也不补默认值")
    void promptComesFromTheRequestVerbatim() {
        Rendered prompt = startConversation(
                AgentRequest.builder()
                        .contextSpec(ContextSpec.builder()
                                .context(List.of(UserMessageEntity.from("hi")))
                                .systemPrompt("  你是后端工程师，只回答结论。  ")
                                .build())
                        .build(),
                sandbox(), McpToolScope.EMPTY);

        assertTrue(prompt.system().contains("## Business Prompt\n你是后端工程师，只回答结论。"),
                "首尾空白被收掉，正文一字不改");
        assertFalse(prompt.system().contains("%s"), "框架不引入任何占位符");
    }

    /**
     * The prompt publishes the first level of progressive disclosure only — one line per server,
     * carrying its description and its tool count. Tool names wait for {@code list_mcp_tools} and
     * schemas for {@code search_tool}, so a summary in the prompt is never a declaration.
     */
    @Test
    @DisplayName("提示词只到服务级（名字+描述+工具数），工具名与 schema 都不进")
    void remoteServersAppearAsResumesOnly() {
        Rendered prompt = startConversation(
                AgentRequest.builder().contextSpec(ContextSpec.builder().context(List.of(UserMessageEntity.from("hi"))).build()).build(),
                sandbox(), scopeOf(githubTools()));

        assertTrue(prompt.dynamic().contains("- github - GitHub remote tools - 2 tools"),
                "一行一个服务：名字 + 描述 + 工具数");
        assertFalse(prompt.dynamic().contains("mcp_github_get_me"), "工具级留给 list_mcp_tools，不进提示词");
        assertFalse(prompt.dynamic().contains("create_issue"), "工具名不进提示词");
        assertFalse(prompt.dynamic().contains("owner"), "参数名只存在于 schema，不该出现在提示词里");
        assertFalse(prompt.dynamic().contains("properties"), "参数 schema 不该出现在提示词里");
    }

    // --- helpers ---------------------------------------------------------------------------

    /** The two halves of the first round: the cacheable system prompt, and the per-round context. */
    private record Rendered(String system, String dynamic) {
    }

    private Rendered startConversation(AgentRequest request, Workspace workspace, McpToolScope scope) {
        Execution execution = Execution.builder()
                .id("e-1").agentId("a")
                .messages(new ArrayList<>(List.of(UserMessageEntity.from("hi"))))
                .agentRequest(request)
                .build();

        conversations.startConversation(execution, workspace, scope);

        List<Message> messages = execution.getMessages();
        SystemMessageEntity leading = assertInstanceOf(SystemMessageEntity.class, messages.getFirst(),
                "首条消息必须是系统提示词");

        int dynamicIndex = -1;
        for (int i = 0; i < messages.size(); i++) {
            if (messages.get(i) instanceof UserMessageEntity user && user.hasContextContract()) {
                dynamicIndex = i;
            }
        }
        String dynamic = dynamicIndex < 0 ? "" : messages.get(dynamicIndex).text();

        assertEquals("hi", messages.getLast().text(), "本轮用户消息必须是最后一条");
        assertTrue(messages.getLast() instanceof UserMessageEntity, "用户消息紧随其后，不被挤走");
        if (dynamicIndex < 0) {
            assertEquals(2, messages.size(), "系统提示词 + 用户消息，没有多余条目");
        } else {
            assertEquals(dynamicIndex, messages.size() - 2,
                    "动态上下文只能有一个，且必须紧贴在本轮用户消息之前");
            assertEquals(3, messages.size(), "系统提示词 + 动态上下文 + 用户消息");
        }
        return new Rendered(leading.getText(), dynamic);
    }

    private static void print(Rendered prompt) {
        System.out.println("========== SYSTEM PROMPT ==========");
        System.out.println(prompt.system().replace(" ", "·").replace("\n", "\n| "));
        System.out.println("========== DYNAMIC CONTEXT ==========");
        System.out.println(prompt.dynamic().replace(" ", "·").replace("\n", "\n| "));
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

}
