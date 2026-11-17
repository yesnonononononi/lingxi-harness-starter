package com.summit.core.tool;

import com.summit.core.agent.AgentRequest;
import com.summit.core.agent.Execution;
import com.summit.core.agent.ToolSpec;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards "which static tools does this run see" against the two ways it drifts.
 *
 * <p>An empty whitelist admits no application tool but must still report the framework kernel
 * tools, because the model is served those regardless — reporting zero there undercounts the
 * request and disagrees with what the provider actually receives. Selection is also sorted by name
 * so the declaration order never depends on registration order.</p>
 */
class ToolVisibilityTest {

    @Test
    void emptyWhitelistStillSelectsTheKernelTools() {
        List<ToolDefinition<?>> registered = List.of(applicationTool("project_info"), kernelTool("compact_context"));

        for (List<String> allowed : List.of(List.<String>of())) {
            assertEquals(List.of("compact_context"), names(ToolVisibility.select(registered, allowed)));
        }
        assertEquals(List.of("compact_context"), names(ToolVisibility.select(registered, null)),
                "a null whitelist admits no application tool but keeps the kernel tools");
    }

    @Test
    void whitelistAddsApplicationToolsNextToTheKernelTools() {
        List<ToolDefinition<?>> registered = List.of(
                applicationTool("project_info"), kernelTool("compact_context"), applicationTool("read_file"));

        assertEquals(List.of("compact_context", "project_info"),
                names(ToolVisibility.select(registered, List.of(" project_info "))));
    }

    @Test
    void selectionIsSortedByNameRegardlessOfRegistrationOrder() {
        List<ToolDefinition<?>> registered = List.of(
                applicationTool("zeta"), kernelTool("search_tool"), applicationTool("alpha"));

        assertEquals(List.of("alpha", "search_tool", "zeta"),
                names(ToolVisibility.select(registered, List.of("zeta", "alpha"))));
    }

    @Test
    void nothingSelectedWhenNothingIsRegistered() {
        assertTrue(ToolVisibility.select(List.of(), List.of("project_info")).isEmpty());
        assertTrue(ToolVisibility.select(null, null).isEmpty());
    }

    /** The budget reporter reads the registry, so the two paths must agree on the same request. */
    @Test
    void registryReportsTheKernelToolsUnderAnEmptyWhitelist() {
        ToolRegistry registry = new ToolRegistry(List.of(applicationTool("project_info"), kernelTool("compact_context")));

        assertEquals(List.of("compact_context"), names(registry.registeredTools(execution(null))));
        assertEquals(List.of("compact_context"), names(registry.registeredTools(execution(List.of()))));
        assertEquals(List.of("compact_context", "project_info"),
                names(registry.registeredTools(execution(List.of("project_info")))));
    }

    @Test
    void registryReportsNothingWithoutAnExecution() {
        ToolRegistry registry = new ToolRegistry(List.of(kernelTool("compact_context")));

        assertTrue(registry.registeredTools(null).isEmpty());
    }

    private Execution execution(List<String> toolList) {
        return Execution.builder()
                .agentRequest(AgentRequest.builder().toolSpec(ToolSpec.builder().toolList(toolList).build()).build())
                .build();
    }

    private List<String> names(List<ToolDefinition<?>> definitions) {
        return definitions.stream().map(ToolDefinition::name).toList();
    }

    private ToolDefinition<ToolExecutor> applicationTool(String name) {
        return definition(name);
    }

    private ToolDefinition<ToolExecutor> kernelTool(String name) {
        assertTrue(KernelTools.isKernel(name), "test fixture must use a real kernel tool name: " + name);
        return definition(name);
    }

    private ToolDefinition<ToolExecutor> definition(String name) {
        return ToolDefinition.<ToolExecutor>builder()
                .id(name).name(name).description("tool")
                .parametersJsonSchema("{\"type\":\"object\",\"properties\":{}}")
                .executor(execution -> ToolExecuteResult.success("ok"))
                .maxOutput(100).timeout(2L).concurrentPolicy(ConcurrentPolicy.READ_ONLY)
                .build();
    }
}
