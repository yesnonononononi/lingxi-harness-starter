package com.summit.core.tool;

import com.summit.core.agent.ToolSpec;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the two halves of the admission rule.
 *
 * <p>An application tool is admitted only when the request names it: {@code null} and empty both
 * mean "no application tool", because an absent list must never widen to "everything registered".
 * Framework kernel tools are the deliberate exception — running a loop needs compaction, MCP
 * discovery, the MCP gateway and skill loading whether or not the caller remembered to ask, so they
 * are admitted with no whitelist at all.</p>
 */
class ToolWhitelistAdmissionTest {

    @Test
    void kernelToolsAreAdmittedWithoutAnyWhitelist() {
        for (String name : List.of(KernelTools.SEARCH, KernelTools.LIST_MCP, KernelTools.CALL_MCP,
                KernelTools.READ_SKILL, KernelTools.COMPACT_CONTEXT)) {
            assertTrue(definition(name).allowedFor((List<String>) null), name);
            assertTrue(definition(name).allowedFor(List.of()), name);
            assertTrue(definition(name).allowedFor(List.of("something_else")), name);
        }
    }

    @Test
    void applicationToolsStillRequireAnExplicitEntry() {
        ToolDefinition<ToolExecutor> tool = definition("project_info");

        assertFalse(tool.allowedFor((List<String>) null));
        assertFalse(tool.allowedFor(List.of()));
        assertFalse(tool.allowedFor(List.of("other")));
        assertTrue(tool.allowedFor(List.of("project_info")));
        assertTrue(tool.allowedFor(List.of(" other ", " project_info ")));

        ToolSpec blacklisted = ToolSpec.builder()
                .toolList(List.of("project_info"))
                .forbiddenTools(List.of(" project_info "))
                .build();
        assertFalse(tool.allowedFor(blacklisted));
        assertTrue(tool.allowedFor(blacklisted.getToolList()), "static model visibility keeps blacklisted tools");
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
