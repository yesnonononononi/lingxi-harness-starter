package com.summit.core.json;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.InvalidTypeIdException;
import com.fasterxml.jackson.databind.jsontype.NamedType;
import com.summit.core.agent.*;
import com.summit.core.conf.ModelConfig;
import com.summit.core.conversation.api.ToolCallRequest;
import com.summit.core.conversation.message.*;
import com.summit.core.conversation.message.content.*;
import com.summit.core.memory.MemoryConfig;
import com.summit.core.memory.MemoryManagerMode;
import com.summit.core.workspace.BasicWorkspaceSpec;
import com.summit.core.workspace.WorkspaceSpec;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ExecutionJsonTest {
    private final ObjectMapper mapper = ExecutionJson.newObjectMapper();

    @Test
    void ignoresRetiredWriteTrackingInLegacySnapshots() throws Exception {
        Execution restored = mapper.readValue(
                "{\"id\":\"legacy\",\"agentRequest\":{},\"writeToolExecuted\":true}", Execution.class);
        assertEquals("legacy", restored.getId());
        assertFalse(mapper.readTree(mapper.writeValueAsString(restored)).has("writeToolExecuted"));
        assertThrows(com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException.class,
                () -> mapper.readValue("{\"unexpectedField\":true}", Execution.class));
    }

    /** Retired per-call fields survive in stored snapshots and must restore without failure. */
    @Test
    void ignoresRetiredIntentionInLegacyToolCalls() throws Exception {
        Execution restored = mapper.readValue(
                "{\"id\":\"legacy\",\"agentRequest\":{},\"aiMessage\":{\"type\":\"AI\",\"text\":\"answer\",\"toolCalls\":"
                        + "[{\"id\":\"c1\",\"name\":\"search\",\"arguments\":\"{}\",\"intention\":\"lookup\"}]}}",
                Execution.class);
        ToolCallRequest call = restored.getAiMessage().getToolCalls().getFirst();
        assertEquals("c1", call.id());
        assertEquals("{}", call.arguments());
        assertFalse(mapper.readTree(mapper.writeValueAsString(call)).has("intention"));
    }


    @Test
    void restoresExecutionAndEveryMessageAndContentType() throws Exception {
        AiMessageEntity ai = AiMessageEntity.builder().text("answer").thinking("reason")
                .toolCalls(List.of(new ToolCallRequest("call-1", "search", 0, "{}"),
                        new ToolCallRequest("call-2", "search", 1, "{\"query\":\"next\"}"))).build();
        List<Message> messages = List.of(
                SystemMessageEntity.builder().text("system").build(),
                UserMessageEntity.builder().content(List.of(TextContent.from("question"),
                        ImageContent.from(Image.from("aGVsbG8=")), new AudioContent(),
                        new VideoContent(), new PdfFileContent())).build(),
                ai, ToolMessageEntity.builder().id("call-1").name("search").text("result").build());
        Instant timestamp = Instant.parse("2026-09-27T01:02:03.123456789Z");
        Execution original = Execution.builder().id("exec-1").agentId("agent-1")
                .executionState(ExecutionState.SUSPENDED).createAt(timestamp).startAt(timestamp)
                .completedAt(timestamp).messages(messages).aiMessage(ai)
                .tokenUsage(TokenUsageEntity.of(12, 8, 4)).thinking(true).streaming(true)
                .maxSteps(9).errorMessage("example")
                .agentRequest(AgentRequest.builder().executionId("exec-1").messages(messages)
                        .systemPrompt("system").task(Collections.singletonList("task")).toolList(List.of("search"))
                        .workspaceSpec(new BasicWorkspaceSpec("local", "/tmp/work", "tenant", Map.of("x", "y")))
                        .runtimeParameters(AgentRuntimeParameters.builder().allowOutsideWorkspace(true)
                                .attributes(Map.of("attempt", 2, "labels", List.of("a", "b"))).build())
                        .modelConfig(ModelConfig.builder().baseUrl("https://example.invalid")
                                .apiKey("test-key").modelName("test-model").timeout(Duration.ofMillis(1250)).build())
                        .build()).build();

        String json = mapper.writeValueAsString(original);
        Execution restored = mapper.readValue(json, Execution.class);

        assertEquals(List.of(0, 1), restored.getAiMessage().getToolCalls().stream()
                .map(ToolCallRequest::requestIndex).toList());
        assertEquals(mapper.readTree(json), mapper.readTree(mapper.writeValueAsString(restored)));
        assertEquals(timestamp, restored.getCreateAt());
        assertEquals(ExecutionState.SUSPENDED, restored.getExecutionState());
        assertInstanceOf(SystemMessageEntity.class, restored.getMessages().get(0));
        UserMessageEntity user = assertInstanceOf(UserMessageEntity.class, restored.getMessages().get(1));
        assertInstanceOf(AiMessageEntity.class, restored.getMessages().get(2));
        assertInstanceOf(ToolMessageEntity.class, restored.getMessages().get(3));
        assertInstanceOf(TextContent.class, user.getContent().get(0));
        assertEquals("aGVsbG8=", assertInstanceOf(ImageContent.class, user.getContent().get(1)).getImage().getBase64Data());
        assertInstanceOf(AudioContent.class, user.getContent().get(2));
        assertInstanceOf(VideoContent.class, user.getContent().get(3));
        assertInstanceOf(PdfFileContent.class, user.getContent().get(4));
        assertInstanceOf(UserMessageEntity.class, restored.getAgentRequest().getMessages().get(1));
        assertEquals(original.getAgentRequest().getWorkspaceSpec(), restored.getAgentRequest().getWorkspaceSpec());
        assertEquals(Duration.ofMillis(1250), restored.getAgentRequest().getModelConfig().getTimeout());
        assertEquals(ai.getToolCalls(), restored.getAiMessage().getToolCalls());
        assertEquals(12, restored.getTokenUsage().getTotalTokens());
    }

    @Test
    void restoresMinimalExecutionAndBuilderDefaults() throws Exception {
        Execution execution = mapper.readValue("{\"id\":\"minimal\",\"agentRequest\":{}}", Execution.class);
        assertEquals("minimal", execution.getId());
        assertNull(execution.getMessages());
        assertEquals(List.of(), execution.getAgentRequest().getMessages());
        assertNotNull(execution.getAgentRequest().getRuntimeParameters());
        assertNull(execution.getAgentRequest().getWorkspaceSpec());
        assertEquals(mapper.valueToTree(execution),
                mapper.valueToTree(mapper.readValue(mapper.writeValueAsString(execution), Execution.class)));
    }

    @Test
    void supportsExplicitlyRegisteredWorkspaceImplementations() throws Exception {
        ObjectMapper custom = ExecutionJson.newObjectMapper(new NamedType(TestWorkspace.class, "test"));
        Execution original = Execution.builder().agentRequest(AgentRequest.builder()
                .workspaceSpec(new TestWorkspace("test", "/work", "extra")).build()).build();
        String json = custom.writeValueAsString(original);
        Execution restored = custom.readValue(json, Execution.class);
        assertEquals(original.getAgentRequest().getWorkspaceSpec(), restored.getAgentRequest().getWorkspaceSpec());
        assertThrows(InvalidTypeIdException.class, () -> mapper.readValue(json, Execution.class));
    }

    @Test
    void rejectsUnknownMessageTypes() {
        assertThrows(InvalidTypeIdException.class, () -> mapper.readValue(
                "{\"messages\":[{\"type\":\"java.lang.Runtime\"}]}", Execution.class));
    }

    @Test
    void restoresSuspendedExecutionWithMemoryConfiguration() throws Exception {
        MemoryConfig memory = MemoryConfig.builder().credential(".agent/MEMORY.md")
                .mode(MemoryManagerMode.ALLOW_WRITE).maxChars(4096).build();
        Execution original = Execution.builder().id("memory-execution")
                .executionState(ExecutionState.SUSPENDED)
                .messages(List.of(SystemMessageEntity.builder().text("Loaded memory snapshot").build()))
                .agentRequest(AgentRequest.builder().memoryConfig(memory)
                        .messages(List.of(UserMessageEntity.from("Continue the task"))).build())
                .build();

        String json = mapper.writeValueAsString(original);
        Execution restored = mapper.readValue(json, Execution.class);

        assertEquals(memory, restored.getAgentRequest().getMemoryConfig());
        assertEquals(ExecutionState.SUSPENDED, restored.getExecutionState());
        assertEquals("Loaded memory snapshot", restored.getMessages().getFirst().text());
        assertEquals(mapper.readTree(json), mapper.readTree(mapper.writeValueAsString(restored)));
    }

    public record TestWorkspace(String provider, String workDir, String extra) implements WorkspaceSpec {}
}
