package com.summit.runtime.tool;

import com.summit.core.conversation.api.ToolCallRequest;
import com.summit.core.conversation.event.RuntimeEventPublisher;
import com.summit.core.conversation.event.ToolCallEndEvent;
import com.summit.core.runtime.RuntimeEnvironment;
import com.summit.core.runtime.RuntimeListener;
import com.summit.core.runtime.loop.ExecutionControlSignal;
import com.summit.core.runtime.workspace.Workspace;
import com.summit.core.tool.ConcurrentPolicy;
import com.summit.core.tool.ToolCallStatus;
import com.summit.core.tool.ToolDefinition;
import com.summit.core.tool.ToolExecutionContext;
import com.summit.core.tool.ToolExecuteCommand;
import com.summit.core.tool.ToolExecuteResult;
import com.summit.core.tool.ToolRegistry;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A stop request must take a running tool back instead of waiting for the tool's own timeout.
 *
 * <p>The signal alone is a flag on another thread: it does not complete the future the manager
 * waits on. These cases pin the two halves that make it effective — the sliced wait that notices
 * the flag, and the cancellation that interrupts the worker (which is what releases a blocking
 * command's process).</p>
 */
class ToolExecutionCancelTest {

    @Test
    void stopRequestInterruptsRunningToolInsteadOfWaitingForItsTimeout() throws Exception {
        AtomicBoolean workerInterrupted = new AtomicBoolean(false);
        CountDownLatch started = new CountDownLatch(1);
        Queue<ToolCallEndEvent> events = new ConcurrentLinkedQueue<>();

        ToolDefinition<?> tool = ToolDefinition.builder().id("slow").name("slow").maxOutput(100)
                .timeout(60L).concurrentPolicy(ConcurrentPolicy.SERIAL_MUTATION)
                .executor(execution -> {
                    started.countDown();
                    try {
                        Thread.sleep(60_000);
                    } catch (InterruptedException stop) {
                        workerInterrupted.set(true);
                        Thread.currentThread().interrupt();
                        return ToolExecuteResult.err("command stopped");
                    }
                    return ToolExecuteResult.success("never reached");
                })
                .build();
        ExecutionControlSignal signal = new ExecutionControlSignal("exec-1");
        ToolExecuteCommand command = command(tool, signal);

        long began = System.nanoTime();
        try (DefaultToolExecutionManager manager = manager(tool, events);
             ScheduledExecutorService stopper = Executors.newSingleThreadScheduledExecutor();
             ExecutorService caller = Executors.newSingleThreadExecutor()) {
            Future<List<ToolExecuteResult>> pending = caller.submit(() -> manager.execute(command));
            assertTrue(started.await(5, TimeUnit.SECONDS), "工具未启动");
            stopper.schedule(signal::requireCancel, 300, TimeUnit.MILLISECONDS);

            List<ToolExecuteResult> results = pending.get(5, TimeUnit.SECONDS);

            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - began);
            assertTrue(elapsedMillis < 5_000, "停止请求未生效，等待了 " + elapsedMillis + "ms");
            assertFalse(results.getFirst().isSuccess());
            assertEquals("call-1", results.getFirst().getId(), "取消结果必须带上工具调用 id");
            assertEquals(ToolCallStatus.CANCELLED, events.peek().resultStatus());
        }
        assertTrue(workerInterrupted.get(), "工具线程未被中断 ⇒ 阻塞中的进程杀不掉");
    }

    @Test
    void alreadyCancelledBatchDoesNotStartTheToolButStillAnswersTheCall() throws Exception {
        AtomicBoolean invoked = new AtomicBoolean(false);
        Queue<ToolCallEndEvent> events = new ConcurrentLinkedQueue<>();
        ToolDefinition<?> tool = ToolDefinition.builder().id("slow").name("slow").maxOutput(100)
                .timeout(60L).concurrentPolicy(ConcurrentPolicy.SERIAL_MUTATION)
                .executor(execution -> {
                    invoked.set(true);
                    return ToolExecuteResult.success("ok");
                })
                .build();
        ExecutionControlSignal signal = new ExecutionControlSignal("exec-1");
        signal.requireCancel();

        try (DefaultToolExecutionManager manager = manager(tool, events)) {
            List<ToolExecuteResult> results = manager.execute(command(tool, signal));

            assertFalse(invoked.get(), "已取消的批次不应启动工具");
            assertEquals("call-1", results.getFirst().getId());
            assertEquals(ToolCallStatus.CANCELLED, events.peek().resultStatus(), "缺少结束回执会让卡片停在 calling");
        }
    }

    @Test
    void commandWithoutSignalStillRunsTimedTools() {
        Queue<ToolCallEndEvent> events = new ConcurrentLinkedQueue<>();
        ToolDefinition<?> tool = ToolDefinition.builder().id("quick").name("quick").maxOutput(100)
                .timeout(5L).concurrentPolicy(ConcurrentPolicy.SERIAL_MUTATION)
                .executor(execution -> ToolExecuteResult.success("done"))
                .build();

        try (DefaultToolExecutionManager manager = manager(tool, events)) {
            ToolExecuteCommand command = ToolExecuteCommand.builder()
                    .requests(List.of(new ToolCallRequest("call-1", "quick", 0, "{}")))
                    .executionId("exec-1")
                    .workspace(workspace())
                    .allowedTools(List.of("quick"))
                    .responseId("1234567890123456789")
                    .build();

            List<ToolExecuteResult> results = manager.execute(command);

            assertTrue(results.getFirst().isSuccess(), "无 signal 的命令不应被空指针或类型转换打断");
            assertEquals(ToolCallStatus.COMPLETED, events.peek().resultStatus());
        }
    }

    private ToolExecuteCommand command(ToolDefinition<?> tool, ExecutionControlSignal signal) {
        return ToolExecuteCommand.builder()
                .requests(List.of(new ToolCallRequest("call-1", tool.name(), 0, "{}")))
                .executionId("exec-1")
                .workspace(workspace())
                .allowedTools(List.of(tool.name()))
                .responseId("1234567890123456789")
                .signal(signal)
                .build();
    }

    private DefaultToolExecutionManager manager(ToolDefinition<?> tool, Queue<ToolCallEndEvent> events) {
        RuntimeListener listener = new RuntimeListener() {
            @Override
            public void onToolCallOutput(ToolCallEndEvent event) {
                events.add(event);
            }
        };
        ToolExecutionContext context = ToolExecutionContext.builder()
                .toolRegistry(new ToolRegistry(List.of(tool)))
                .runtimeEventPublisher(new RuntimeEventPublisher(List.of(listener)))
                .build();
        return new DefaultToolExecutionManager(context,
                invocation -> invocation.getMethod().invoke(invocation.getTarget(), invocation.getContext()),
                List.of());
    }

    private Workspace workspace() {
        return new Workspace() {
            @Override
            public String id() {
                return "workspace-1";
            }

            @Override
            public RuntimeEnvironment runtimeEnvironment() {
                return null;
            }

            @Override
            public String workDir() {
                return ".";
            }

            @Override
            public Path resolve(String path) {
                return Path.of(path);
            }
        };
    }
}
