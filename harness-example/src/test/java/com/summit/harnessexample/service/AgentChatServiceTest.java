package com.summit.harnessexample.service;

import com.summit.core.runtime.workspace.Workspace;
import com.summit.harnessexample.ActiveWorkspace;
import com.summit.harnessexample.Demo;
import com.summit.harnessexample.SseEventPublisher;
import com.summit.harnessexample.common.ApiException;
import com.summit.harnessexample.dto.ChatRequest;
import com.summit.harnessexample.session_policy.RedisConversationRepository;
import com.summit.core.agent.Execution;
import com.summit.core.agent.ExecutionState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Lifecycle-bookkeeping rules of {@link AgentChatService}: every run of a session must stay tracked (so it can be stopped and counted), and lifecycle commands are applied or rejected deliberately. */
@ExtendWith(MockitoExtension.class)
class AgentChatServiceTest {

    @Mock
    private Demo demo;
    @Mock
    private SseEventPublisher sseEventPublisher;
    @Mock
    private ActiveWorkspace activeWorkspace;
    @Mock
    private Workspace workspace;
    @Mock
    private RedisConversationRepository conversationRepository;

    private AgentChatService service;

    /** Counts down once the expected number of demo runs has started. */
    private CountDownLatch started;

    /** Released by each test to unblock the (otherwise pending) demo runs. */
    private CountDownLatch release;

    @BeforeEach
    void setUp() {
        service = new AgentChatService(demo, sseEventPublisher, activeWorkspace, conversationRepository);
        lenient().when(activeWorkspace.get()).thenReturn(workspace);
        lenient().when(conversationRepository.find(any())).thenReturn(Optional.empty());
    }

    @Test
    void keepsEveryRunOfTheSameSessionTracked() throws Exception {
        givenRunsThatBlockUntilReleased(2);

        Map<String, Object> first = service.chat(request("first", "s1"));
        Map<String, Object> second = service.chat(request("second", "s1"));
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

        Set<CompletableFuture<Void>> tasks = runningTasksOf(service).get("s1");
        assertThat(tasks).hasSize(2);
        assertThat(service.runningCount()).isEqualTo(1);

        service.stop("s1");

        // Neither execution may be forgotten: both tracked tasks are cancelled.
        assertThat(tasks).allMatch(CompletableFuture::isCancelled);
        release.countDown();
    }

    @Test
    void stopWithoutSessionIdInterruptsEveryRunningTask() throws Exception {
        givenRunsThatBlockUntilReleased(1);

        Map<String, Object> submitted = service.chat(request("go", "s1"));
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        Set<CompletableFuture<Void>> tasks = runningTasksOf(service).get("s1");

        Map<String, Object> result = service.stop(null);

        assertThat(result).containsEntry("applied", true);
        assertThat(tasks).allMatch(CompletableFuture::isCancelled);
        release.countDown();
    }

    @Test
    void dropsTheSessionOnceAllItsRunsFinish() throws Exception {
        givenRunsThatReturnImmediately();

        service.chat(request("go", "s1"));
        awaitNoRunningSessions();

        assertThat(service.runningCount()).isZero();
        assertThat(runningTasksOf(service)).isEmpty();
    }

    @Test
    void controlOnUnknownSessionFailsWithNotFound() {
        assertThatThrownBy(() -> service.stop("missing"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo(404));
    }

    @Test
    void controlWithoutRunningSessionsIsIgnored() {
        Map<String, Object> result = service.stop(null);

        assertThat(result).containsEntry("applied", false);
    }

    @Test
    void chatRejectsBlankInput() {
        assertThatThrownBy(() -> service.chat(request("   ", null)))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo(400));
        verifyNoInteractions(demo);
    }

    // ------------------------------------------------------------------ helpers

    /** Demo stub whose runs block until {@link #release} is counted down, so tasks stay in flight. */
    private void givenRunsThatBlockUntilReleased(int expectedStarts) {
        started = new CountDownLatch(expectedStarts);
        release = new CountDownLatch(1);
        doAnswer(invocation -> {
            started.countDown();
            release.await(10, TimeUnit.SECONDS);
            return completedExecution((String) invocation.getArgument(1));
        }).when(demo).chat(any(), any(), any(), any(), any(), any());
    }

    /** Demo stub whose runs finish at once, to observe the post-completion cleanup. */
    private void givenRunsThatReturnImmediately() {
        doAnswer(invocation -> completedExecution((String) invocation.getArgument(1)))
                .when(demo).chat(any(), any(), any(), any(), any(), any());
    }

    private static ChatRequest request(String input, String sessionId) {
        return new ChatRequest(input, "default", sessionId, null, null, null);
    }

    private static Execution completedExecution(String id) {
        return Execution.builder().id(id).executionState(ExecutionState.COMPLETED)
                .messages(java.util.List.of()).build();
    }

    private void awaitNoRunningSessions() throws InterruptedException {
        for (int i = 0; i < 500 && service.runningCount() > 0; i++) {
            Thread.sleep(10);
        }
    }

    /** Reads the private running-task registry so a test can assert on what is actually tracked. */
    @SuppressWarnings("unchecked")
    private static Map<String, Set<CompletableFuture<Void>>> runningTasksOf(AgentChatService service) throws Exception {
        Field field = AgentChatService.class.getDeclaredField("runningTasks");
        field.setAccessible(true);
        return (Map<String, Set<CompletableFuture<Void>>>) field.get(service);
    }
}
