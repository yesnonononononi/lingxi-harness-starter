package com.summit.core.tool;

import com.summit.core.agent.ToolSpec;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the two halves of the admission rule, and that they stay two halves.
 *
 * <p>Model visibility: an application tool appears in the declarations only when the request names
 * it — {@code null} and empty both mean "no application tool", because an absent list must never
 * widen to "everything registered". Framework kernel tools are the deliberate exception: running a
 * loop needs compaction, MCP discovery, the MCP gateway and skill loading whether or not the caller
 * remembered to ask.</p>
 *
 * <p>Execution adds one thing to that: the per-round blacklist. It must never leak into visibility,
 * or disabling a tool would rewrite the model's declarations and invalidate the prompt prefix
 * cache the whole split exists to protect.</p>
 */
class ToolWhitelistAdmissionTest {

    @Test
    void kernelToolsAreVisibleWithoutAnyWhitelist() {
        for (String name : List.of(KernelTools.SEARCH, KernelTools.LIST_MCP, KernelTools.CALL_MCP,
                KernelTools.READ_SKILL, KernelTools.COMPACT_CONTEXT)) {
            assertTrue(definition(name).visibleToModel(null), name);
            assertTrue(definition(name).visibleToModel(List.of()), name);
            assertTrue(definition(name).visibleToModel(List.of("something_else")), name);
        }
    }

    @Test
    void applicationToolsStillRequireAnExplicitEntry() {
        ToolDefinition<ToolExecutor> tool = definition("project_info");

        assertFalse(tool.visibleToModel(null));
        assertFalse(tool.visibleToModel(List.of()));
        assertFalse(tool.visibleToModel(List.of("other")));
        assertTrue(tool.visibleToModel(List.of("project_info")));
        assertTrue(tool.visibleToModel(List.of(" other ", " project_info ")));
    }

    @Test
    void blacklistBlocksExecutionButNeverVisibility() {
        ToolDefinition<ToolExecutor> tool = definition("project_info");
        ToolSpec blacklisted = ToolSpec.builder()
                .toolList(List.of("project_info"))
                .forbiddenTools(List.of(" project_info "))
                .build();

        assertTrue(tool.forbiddenBy(blacklisted));
        assertFalse(tool.executableUnder(blacklisted));
        assertTrue(tool.visibleToModel(blacklisted.getToolList()), "static model visibility keeps blacklisted tools");
    }

    @Test
    void executionFollowsTheWhitelistWhenNothingIsBlacklisted() {
        ToolDefinition<ToolExecutor> tool = definition("project_info");
        ToolSpec named = ToolSpec.builder().toolList(List.of("project_info")).build();
        ToolSpec notNamed = ToolSpec.builder().toolList(List.of("other")).build();

        assertFalse(tool.forbiddenBy(named), "an empty blacklist names nothing");
        assertTrue(tool.executableUnder(named));
        assertFalse(tool.executableUnder(notNamed));
        assertFalse(definition("other").executableUnder(named));
        assertTrue(definition(KernelTools.COMPACT_CONTEXT).executableUnder(notNamed));
    }

    @Test
    void aBlacklistNamingAnotherToolDoesNotBlockThisOne() {
        ToolDefinition<ToolExecutor> tool = definition("project_info");
        ToolSpec otherBlacklisted = ToolSpec.builder()
                .toolList(List.of("project_info"))
                .forbiddenTools(List.of("read_file"))
                .build();

        assertFalse(tool.forbiddenBy(otherBlacklisted));
        assertTrue(tool.executableUnder(otherBlacklisted));
    }

    @Test
    void blacklistAppliesToKernelToolsToo() {        ToolDefinition<ToolExecutor> gateway = definition(KernelTools.CALL_MCP);
        ToolSpec blacklisted = ToolSpec.builder()
                .forbiddenTools(List.of(KernelTools.CALL_MCP))
                .build();

        assertTrue(gateway.visibleToModel(blacklisted.getToolList()), "the gateway stays declared");
        assertFalse(gateway.executableUnder(blacklisted), "but the round can still switch it off");
    }

    @Test
    void absentSpecAdmitsOnlyTheKernelTools() {
        ToolDefinition<ToolExecutor> application = definition("project_info");
        ToolDefinition<ToolExecutor> kernel = definition(KernelTools.COMPACT_CONTEXT);

        assertFalse(application.executableUnder(null));
        assertTrue(kernel.executableUnder(null));
        assertFalse(application.forbiddenBy(null));
    }

    @Test
    void onlyTheFiveFrameworkToolsCountAsKernel() {
        assertTrue(KernelTools.isKernel("search_tool"));
        assertTrue(KernelTools.isKernel("list_mcp_tools"));
        assertTrue(KernelTools.isKernel("call_mcp_tool"));
        assertTrue(KernelTools.isKernel("read_skill"));
        assertTrue(KernelTools.isKernel("compact_context"));

        assertFalse(KernelTools.isKernel(null));
        assertFalse(KernelTools.isKernel(""));
        assertFalse(KernelTools.isKernel("read_file"));
        assertFalse(KernelTools.isKernel("search_tool "));
    }

    private ToolDefinition<ToolExecutor> definition(String name) {
        return ToolDefinition.<ToolExecutor>builder()
                .id("id").name(name).description("tool")
                .parametersJsonSchema("{\"type\":\"object\",\"properties\":{}}")
                .executor(execution -> ToolExecuteResult.success("ok"))
                .maxOutput(100).timeout(2L).concurrentPolicy(ConcurrentPolicy.READ_ONLY)
                .build();
    }
}
