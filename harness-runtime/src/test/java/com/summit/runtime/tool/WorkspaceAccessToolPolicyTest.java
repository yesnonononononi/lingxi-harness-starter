package com.summit.runtime.tool;

import com.summit.core.agent.ToolSpec;
import com.summit.core.conversation.api.ToolCallRequest;
import com.summit.core.conversation.event.RuntimeEventPublisher;
import com.summit.core.conversation.event.ToolCallEndEvent;
import com.summit.core.tool.ConcurrentPolicy;
import com.summit.core.tool.KernelTools;
import com.summit.core.tool.ToolCallStatus;
import com.summit.core.tool.ToolDefinition;
import com.summit.core.tool.ToolExecuteCommand;
import com.summit.core.tool.ToolExecuteResult;
import com.summit.core.tool.ToolExecutionContext;
import com.summit.core.tool.ToolRegistry;
import com.summit.core.workspace.WorkspaceAccessMode;
import com.summit.core.workspace.WorkspaceOptions;
import com.summit.runtime.workspace.LocalWorkspace;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class WorkspaceAccessToolPolicyTest {

    @Test
    void readOnlyBlocksNonReadOnlyToolsBeforeExecutionWhileAdmittingReadOnlyAndKernelTools() {
        AtomicBoolean writeInvoked = new AtomicBoolean(false);
        ToolDefinition<?> readTool = ToolDefinition.builder()
                .id("read_file").name("read_file").maxOutput(1000).timeout(5L)
                .concurrentPolicy(ConcurrentPolicy.READ_ONLY)
                .executor(exec -> ToolExecuteResult.success("read-ok"))
                .build();
        ToolDefinition<?> writeTool = ToolDefinition.builder()
                .id("edit_file").name("edit_file").maxOutput(1000).timeout(5L)
                .concurrentPolicy(ConcurrentPolicy.SERIAL_MUTATION)
                .executor(exec -> {
                    writeInvoked.set(true);
                    return ToolExecuteResult.success("write-ok");
                })
                .build();
        ToolDefinition<?> kernelTool = ToolDefinition.builder()
                .id(KernelTools.SEARCH).name(KernelTools.SEARCH).maxOutput(1000).timeout(5L)
                .concurrentPolicy(ConcurrentPolicy.SERIAL)
                .executor(exec -> ToolExecuteResult.success("kernel-ok"))
                .build();

        List<ToolCallEndEvent> endEvents = new ArrayList<>();
        RuntimeEventPublisher publisher = new RuntimeEventPublisher(List.of()) {
            @Override
            public void onToolCallOutput(ToolCallEndEvent event) {
                endEvents.add(event);
            }
        };

        ToolRegistry registry = new ToolRegistry(List.of(readTool, writeTool, kernelTool));
        DefaultToolExecutionManager manager = new DefaultToolExecutionManager(
                ToolExecutionContext.builder().toolRegistry(registry).runtimeEventPublisher(publisher).build(),
                invocation -> invocation.getMethod().invoke(invocation.getTarget(), invocation.getContext()),
                List.of(new WorkspaceAccessToolPolicy()));

        List<String> whitelist = List.of("read_file", "edit_file");
        LocalWorkspace workspace = new LocalWorkspace("ws-1", ".");
        List<ToolExecuteResult> roResults = manager.execute(ToolExecuteCommand.builder()
                .executionId("exec-1")
                .workspace(workspace)
                .allowedTools(whitelist)
                .workspaceOptions(WorkspaceOptions.builder().accessMode(WorkspaceAccessMode.READ_ONLY).build())
                .requests(List.of(
                        new ToolCallRequest("c1", "read_file", 0, "{}"),
                        new ToolCallRequest("c2", "edit_file", 1, "{}"),
                        new ToolCallRequest("c3", KernelTools.SEARCH, 2, "{}")))
                .build());

        assertTrue(roResults.get(0).isSuccess(), roResults.get(0).getToolOutput());
        assertFalse(roResults.get(1).isSuccess());
        assertFalse(writeInvoked.get(), "forbidden tool executor must never be invoked");
        assertEquals(ToolCallStatus.REJECTED, endEvents.get(1).getResultStatus());
        assertTrue(roResults.get(2).isSuccess(), roResults.get(2).getToolOutput());

        List<ToolExecuteResult> rwResults = manager.execute(ToolExecuteCommand.builder()
                .executionId("exec-2")
                .workspace(workspace)
                .allowedTools(whitelist)
                .workspaceOptions(WorkspaceOptions.builder().accessMode(WorkspaceAccessMode.READ_WRITE).build())
                .requests(List.of(new ToolCallRequest("c4", "edit_file", 0, "{}")))
                .build());

        assertTrue(rwResults.getFirst().isSuccess());
        assertTrue(writeInvoked.get());

        writeInvoked.set(false);
        endEvents.clear();
        List<ToolExecuteResult> blacklistedResults = manager.execute(ToolExecuteCommand.builder()
                .executionId("exec-3")
                .workspace(workspace)
                .toolSpec(ToolSpec.builder()
                        .toolList(whitelist)
                        .forbiddenTools(List.of("edit_file"))
                        .build())
                .workspaceOptions(WorkspaceOptions.builder().accessMode(WorkspaceAccessMode.READ_WRITE).build())
                .requests(List.of(new ToolCallRequest("c5", "edit_file", 0, "{}")))
                .build());

        assertFalse(blacklistedResults.getFirst().isSuccess());
        assertFalse(writeInvoked.get(), "blacklisted tool must be rejected before execution");
        assertEquals(ToolCallStatus.REJECTED, endEvents.getFirst().getResultStatus());
    }
}
