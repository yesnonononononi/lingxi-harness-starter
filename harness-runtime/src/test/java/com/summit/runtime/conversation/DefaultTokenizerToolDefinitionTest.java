package com.summit.runtime.conversation;

import com.summit.core.conversation.message.UserMessageEntity;
import com.summit.core.tool.ConcurrentPolicy;
import com.summit.core.tool.ToolDefinition;
import com.summit.core.tool.ToolExecuteResult;
import com.summit.core.tool.ToolExecutor;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DefaultTokenizerToolDefinitionTest {
    @Test
    void fallbackCountsDefinitionsWithAnUnspecifiedConcurrencyPolicy() {
        ToolDefinition<ToolExecutor> definition = definition(null);
        DefaultTokenizer tokenizer = new DefaultTokenizer();

        int tokens = assertDoesNotThrow(() -> tokenizer.count(null, List.of(definition)));

        assertTrue(tokens > 0);
        assertEquals(tokens, tokenizer.count(List.of(), List.of(definition)));
    }

    @Test
    void fallbackPreservesHistoryWhenAddingDefinitions() {
        ToolDefinition<ToolExecutor> definition = definition(ConcurrentPolicy.READ_ONLY);
        DefaultTokenizer tokenizer = new DefaultTokenizer();
        int definitions = tokenizer.count(null, List.of(definition));

        assertTrue(definitions > 0);
        assertEquals(4 + definitions,
                tokenizer.count(List.of(UserMessageEntity.from("abcdefghijkl")), List.of(definition)));
        assertEquals(0, tokenizer.count(null, List.of()));
    }

    private ToolDefinition<ToolExecutor> definition(ConcurrentPolicy policy) {
        return ToolDefinition.<ToolExecutor>builder().id("lookup").name("lookup").description("Find a file")
                .parametersJsonSchema("{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"}}}")
                .maxOutput(64).timeout(5L).concurrentPolicy(policy)
                .executor(ignored -> ToolExecuteResult.success("ok")).build();
    }
}
