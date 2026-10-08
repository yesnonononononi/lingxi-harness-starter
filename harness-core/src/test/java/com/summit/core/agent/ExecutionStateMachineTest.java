package com.summit.core.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.summit.core.conversation.message.UserMessageEntity;
import com.summit.core.json.ExecutionJson;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The state machine an {@link Execution} must obey.
 *
 * <p>The rules live on the {@code xxxChecked} methods, so they hold no matter who calls: there is no
 * separate checker to reach around. They are pinned here because the states are the invariant, not
 * the convenience of any one caller.</p>
 */
class ExecutionStateMachineTest {

    private final ObjectMapper mapper = ExecutionJson.newObjectMapper();

    // --- legal transitions ----------------------------------------------------------------

    @Test
    @DisplayName("CREATED → RUNNING 是唯一合法的开始路径")
    void startsFromCreated() {
        Execution execution = executionIn(ExecutionState.CREATED);

        execution.startChecked();

        assertEquals(ExecutionState.RUNNING, execution.getExecutionState());
        assertNotNull(execution.getStartAt());
    }

    @Test
    @DisplayName("RUNNING ↔ SUSPENDED 可以反复往返")
    void suspendsAndResumesRepeatedly() {
        Execution execution = executionIn(ExecutionState.CREATED);
        execution.startChecked();

        for (int round = 0; round < 3; round++) {
            execution.suspendChecked();
            assertEquals(ExecutionState.SUSPENDED, execution.getExecutionState(), "第 " + round + " 轮挂起");
            execution.resumeChecked();
            assertEquals(ExecutionState.RUNNING, execution.getExecutionState(), "第 " + round + " 轮恢复");
        }
        assertNotNull(execution.getStartAt(), "反复挂起恢复不重置首次开始时间");
    }

    @Test
    @DisplayName("未开始的执行可以直接失败或取消")
    void failsOrCancelsBeforeStarting() {
        Execution failed = executionIn(ExecutionState.CREATED);
        failed.failChecked("workspace never opened");
        assertEquals(ExecutionState.FAILED, failed.getExecutionState());
        assertEquals("workspace never opened", failed.getErrorMessage());

        Execution cancelled = executionIn(ExecutionState.CREATED);
        cancelled.cancelChecked();
        assertEquals(ExecutionState.CANCELLED, cancelled.getExecutionState());
    }

    // --- illegal transitions --------------------------------------------------------------

    @Test
    @DisplayName("终态是终点：完成后不能再失败、取消或完成")
    void terminalStatesAreFinal() {
        for (ExecutionState terminal : new ExecutionState[]{
                ExecutionState.COMPLETED, ExecutionState.FAILED, ExecutionState.CANCELLED}) {
            Execution execution = executionIn(ExecutionState.RUNNING);
            execution.cancelChecked();
            assertEquals(ExecutionState.CANCELLED, execution.getExecutionState());

            Execution done = executionIn(terminal);
            assertThrows(IllegalStateException.class, done::completeChecked);
            assertThrows(IllegalStateException.class, () -> done.failChecked("late"));
            assertThrows(IllegalStateException.class, done::cancelChecked);
            assertThrows(IllegalStateException.class, done::startChecked);
            assertEquals(terminal, done.getExecutionState(), terminal + " 不该被后续调用改写");
        }
    }

    @Test
    @DisplayName("不能跳过前置状态：挂起前必须运行，恢复前必须挂起")
    void refusesSkippedSteps() {
        Execution created = executionIn(ExecutionState.CREATED);
        assertThrows(IllegalStateException.class, created::suspendChecked);
        assertThrows(IllegalStateException.class, created::resumeChecked);
        assertThrows(IllegalStateException.class, created::completeChecked);

        Execution running = executionIn(ExecutionState.RUNNING);
        assertThrows(IllegalStateException.class, running::resumeChecked,
                "运行中的执行没有挂起，恢复必须被拒");
        assertThrows(IllegalStateException.class, running::startChecked,
                "已经在运行，不能重复开始");
    }

    @Test
    @DisplayName("非法转换报出前态与执行 ID，便于定位")
    void illegalTransitionNamesTheStateAndExecution() {
        Execution execution = executionIn(ExecutionState.CREATED);

        IllegalStateException failure =
                assertThrows(IllegalStateException.class, execution::completeChecked);

        assertTrue(failure.getMessage().contains("CREATED"), failure.getMessage());
        assertTrue(failure.getMessage().contains("exec-1"), failure.getMessage());
    }

    // --- restore path ---------------------------------------------------------------------

    @Test
    @DisplayName("恢复终态沿用已提交的结束时间，而不是盖上 Instant.now()")
    void restoreKeepsTheCommittedFinishTime() {
        Instant committed = Instant.parse("2026-10-04T10:15:30Z");
        Execution execution = executionIn(ExecutionState.SUSPENDED);

        execution.restoreTerminalState(ExecutionState.COMPLETED, committed, null);

        assertEquals(ExecutionState.COMPLETED, execution.getExecutionState());
        assertEquals(committed, execution.getCompletedAt(),
                "恢复出的结束时间必须等于已提交的那个，否则每次读库都会漂移");
    }

    @Test
    @DisplayName("恢复终态保护首次开始时间")
    void restoreKeepsTheFirstStartTime() {
        Instant started = Instant.parse("2026-10-04T09:00:00Z");
        Execution execution = executionIn(ExecutionState.CREATED);
        execution.setStartAt(started);

        execution.restoreTerminalState(ExecutionState.FAILED,
                Instant.parse("2026-10-04T10:00:00Z"), "服务重启时中断");

        assertEquals(started, execution.getStartAt(), "首次开始时间不能被恢复动作改写");
        assertEquals(ExecutionState.FAILED, execution.getExecutionState());
        assertEquals("服务重启时中断", execution.getErrorMessage());
    }

    @Test
    @DisplayName("恢复可覆盖 CREATED / RUNNING / SUSPENDED 三种非终态前态")
    void restoreAcceptsEveryNonTerminalPredecessor() {
        for (ExecutionState predecessor : new ExecutionState[]{
                ExecutionState.CREATED, ExecutionState.RUNNING, ExecutionState.SUSPENDED}) {
            Execution execution = executionIn(predecessor);
            Instant committed = Instant.parse("2026-10-04T11:00:00Z");

            execution.restoreTerminalState(ExecutionState.CANCELLED, committed, null);

            assertEquals(ExecutionState.CANCELLED, execution.getExecutionState(),
                    predecessor + " 应能被校正为终态");
            assertEquals(committed, execution.getCompletedAt());
        }
    }

    @Test
    @DisplayName("恢复不覆盖已有的失败原因")
    void restoreKeepsAnExistingFailureMessage() {
        Execution execution = executionIn(ExecutionState.RUNNING);
        execution.setErrorMessage("原始失败原因");

        execution.restoreTerminalState(ExecutionState.FAILED, Instant.parse("2026-10-04T11:00:00Z"), null);

        assertEquals("原始失败原因", execution.getErrorMessage(),
                "恢复时没有新原因，就不该把已有原因清空");
    }

    @Test
    @DisplayName("已是终态时恢复不改写，两个终态结论不互相覆盖")
    void restoreLeavesAnAlreadyTerminalOutcomeAlone() {
        Instant original = Instant.parse("2026-10-04T08:00:00Z");
        Execution execution = executionIn(ExecutionState.RUNNING);
        execution.setCompletedAt(original);

        // 前态已是终态：无论传入什么结论，都保留已解码的那个
        Execution cancelled = executionIn(ExecutionState.RUNNING);
        cancelled.restoreTerminalState(ExecutionState.CANCELLED, original, null);
        cancelled.restoreTerminalState(ExecutionState.COMPLETED, Instant.parse("2026-10-04T12:00:00Z"), null);

        assertEquals(ExecutionState.CANCELLED, cancelled.getExecutionState(),
                "已取消的执行不能被恢复成已完成");
        assertEquals(original, cancelled.getCompletedAt());
    }

    @Test
    @DisplayName("恢复只接受终态目标，传非终态被拒")
    void restoreRefusesANonTerminalTarget() {
        for (ExecutionState target : new ExecutionState[]{
                ExecutionState.CREATED, ExecutionState.RUNNING, ExecutionState.SUSPENDED}) {
            Execution execution = executionIn(ExecutionState.RUNNING);
            assertThrows(IllegalArgumentException.class,
                    () -> execution.restoreTerminalState(target, Instant.now(), null));
            assertEquals(ExecutionState.RUNNING, execution.getExecutionState());
        }
    }

    @Test
    @DisplayName("恢复结束时间为空时兜底当前时刻，不留空值")
    void restoreFallsBackToNowWhenNoTimeWasCommitted() {
        Execution execution = executionIn(ExecutionState.RUNNING);
        Instant before = Instant.now();

        execution.restoreTerminalState(ExecutionState.COMPLETED, null, null);

        assertNotNull(execution.getCompletedAt());
        assertEquals(ExecutionState.COMPLETED, execution.getExecutionState());
        assertFalse(execution.getCompletedAt().isBefore(before),
                "兜底时间不应早于调用时刻");
    }

    // --- snapshot compatibility -----------------------------------------------------------

    @Test
    @DisplayName("恢复后的执行能正常序列化并再次解码，时间不丢")
    void restoredExecutionSurvivesASnapshotRoundTrip() throws Exception {
        Instant started = Instant.parse("2026-10-04T09:00:00Z");
        Instant completed = Instant.parse("2026-10-04T09:30:00Z");
        Execution execution = executionIn(ExecutionState.RUNNING);
        execution.setStartAt(started);
        execution.restoreTerminalState(ExecutionState.COMPLETED, completed, null);

        String json = mapper.writeValueAsString(execution);
        Execution reloaded = mapper.readValue(json, Execution.class);

        assertEquals(ExecutionState.COMPLETED, reloaded.getExecutionState());
        assertEquals(started, reloaded.getStartAt());
        assertEquals(completed, reloaded.getCompletedAt(),
                "存盘再读回后结束时间必须一致，否则收尸记录会被反复刷新");
        assertNull(reloaded.getMcpConfig(), "新建执行不再复制 MCP 配置");
    }

    @Test
    @DisplayName("快照里已是终态的旧执行照常解码，加载本身不算转换")
    void decodingATerminalSnapshotIsNotATransition() throws Exception {
        Instant completed = Instant.parse("2026-10-04T07:45:00Z");
        String json = "{\"id\":\"old\",\"agentRequest\":{},\"executionState\":\"COMPLETED\","
                + "\"completedAt\":\"" + completed + "\"}";

        Execution restored = mapper.readValue(json, Execution.class);

        assertEquals(ExecutionState.COMPLETED, restored.getExecutionState());
        assertEquals(completed, restored.getCompletedAt());
    }

    private static Execution executionIn(ExecutionState state) {
        return Execution.builder()
                .id("exec-1")
                .agentId("agent-1")
                .executionState(state)
                .messages(new java.util.ArrayList<>(
                        List.of(UserMessageEntity.from("hi"))))
                .agentRequest(AgentRequest.builder()
                        .messages(List.of(UserMessageEntity.from("hi")))
                        .build())
                .build();
    }
}