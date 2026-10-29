package com.summit.adapter.langchain4j.codec.token;

import com.summit.adapter.langchain4j.codec.message.MessageCodecAdapter;
import com.summit.core.conversation.api.ToolCallRequest;
import com.summit.core.conversation.message.AiMessageEntity;
import com.summit.core.conversation.message.Message;
import com.summit.core.conversation.message.SystemMessageEntity;
import com.summit.core.conversation.message.UserMessageEntity;
import dev.langchain4j.model.openai.OpenAiTokenCountEstimator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TokenEstimatorAdapterTest {

    private final TokenEstimatorAdapter estimator = new TokenEstimatorAdapter("gpt-3.5-turbo");
    private final OpenAiTokenCountEstimator upstream = new OpenAiTokenCountEstimator("gpt-3.5-turbo");
    private final MessageCodecAdapter codec = new MessageCodecAdapter();

    @Test
    void preservesUpstreamCountsForOrdinaryAndValidParallelMessages() {
        List<Message> history = List.of(SystemMessageEntity.builder().text("Follow the task").build(),
                UserMessageEntity.from("Inspect the repository"),
                assistant("{\"command\":\"git status\"}"));

        assertEquals(upstream.estimateTokenCountInMessages(codec.toFramework(history)),
                estimator.estimateMessages(history));
        assertEquals(upstream.estimateTokenCountInText("工具参数"), estimator.estimateText("工具参数"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"intent: \"查看相关文件\"}", "{\"command\":", "[1,2]"})
    void malformedParallelArgumentsCanBeCountedAgainAfterFailureRecovery(String arguments) {
        AiMessageEntity assistant = assistant(arguments);
        List<Message> history = new ArrayList<>(List.of(UserMessageEntity.from("Inspect the repository"), assistant));
        assertThrows(RuntimeException.class,
                () -> upstream.estimateTokenCountInMessages(codec.toFramework(history)));

        int initial = assertDoesNotThrow(() -> estimator.estimateMessages(history));
        history.add(SystemMessageEntity.builder().text("Previous execution interrupted; preserve all progress").build());
        history.add(UserMessageEntity.from("Retry the task"));
        int retried = assertDoesNotThrow(() -> estimator.estimateMessages(history));

        assertTrue(initial > estimator.estimateText(arguments));
        assertTrue(retried > initial);
        assertEquals(arguments, assistant.getToolCalls().get(1).arguments());
        assertEquals(2, assistant.getToolCalls().size());
    }

    @Test
    void countsEveryArgumentAndAssistantTextInTheRecoveryPath() {
        String malformed = "{\"command\":";
        AiMessageEntity shortMessage = assistant(malformed);
        AiMessageEntity longMessage = assistant(malformed + "extra content ".repeat(100));
        int shortCount = estimator.estimateMessages(List.of(shortMessage));
        int longCount = estimator.estimateMessages(List.of(longMessage));
        assertTrue(longCount > shortCount + 100);

        AiMessageEntity withoutText = assistant(malformed);
        withoutText.setText(null);
        assertEquals(upstream.estimateTokenCountInText(shortMessage.text()),
                shortCount - estimator.estimateMessages(List.of(withoutText)));
    }

    @Test
    void doesNotHideUnrelatedInvalidMessages() {
        List<Message> history = List.of(new UserMessageEntity(List.of()));
        assertThrows(IllegalArgumentException.class, () -> estimator.estimateMessages(history));
    }

    private AiMessageEntity assistant(String secondArguments) {
        return AiMessageEntity.builder().text("Inspecting the last changes")
                .toolCalls(List.of(ToolCallRequest.builder().id("first").name("read_file")
                                .arguments("{\"path\":\"README.md\"}").build(),
                        ToolCallRequest.builder().id("second").name("execute_command")
                                .arguments(secondArguments).build()))
                .build();
    }
}
