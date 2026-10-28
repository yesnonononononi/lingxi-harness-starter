package com.summit.core.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.summit.core.json.ExecutionJson;
import com.summit.core.runtime.RuntimeEnvironment;
import com.summit.core.runtime.workspace.Workspace;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MemoryContractTest {
    private final ObjectMapper mapper = ExecutionJson.newObjectMapper();

    @Test
    void preservesWholeDocumentWriteTargetAndVersionAcrossJson() throws Exception {
        MemoryDocument document = MemoryDocument.builder().reference("project-memory")
                .version(3).content("original text")
                .lines(List.of(new MemoryDocument.MemoryItem("original text", "project preference", "2026-10-10")))
                .build();
        MemoryDocument loaded = roundTrip(document, MemoryDocument.class);
        MemoryChangeRequest change = MemoryChangeRequest.builder().reference(loaded.getReference())
                .expectedVersion(loaded.getVersion()).oldContent(loaded.getContent())
                .newContent("updated text").operationId("execution-1:write-1").build();
        MemoryChangeRequest restoredChange = roundTrip(change, MemoryChangeRequest.class);

        assertEquals(document, loaded);
        assertEquals(change, restoredChange);
        assertEquals("project-memory", restoredChange.getReference());
        assertEquals(3, restoredChange.getExpectedVersion());
        assertEquals("original text", restoredChange.getOldContent());

        MemoryWriteResult result = MemoryWriteResult.builder().reference(change.getReference())
                .status(MemoryWriteResult.Status.CONFLICT).version(4).message("Document changed").build();
        assertEquals(result, roundTrip(result, MemoryWriteResult.class));
        assertEquals(3, mapper.readValue("{\"version\":3}", MemoryChangeRequest.class).getExpectedVersion());
    }

    @Test
    void supportsApprovalRejectionReplacementAndClearingWithoutAmbiguity() throws Exception {
        MemoryDecision approval = roundTrip(MemoryDecision.approve(), MemoryDecision.class);
        MemoryDecision rejection = roundTrip(MemoryDecision.reject(), MemoryDecision.class);
        MemoryDecision replacement = roundTrip(MemoryDecision.replace("reviewed text"), MemoryDecision.class);
        MemoryDecision clearing = roundTrip(MemoryDecision.replace(""), MemoryDecision.class);

        assertTrue(approval.save());
        assertNull(approval.newMemoryStr());
        assertFalse(rejection.save());
        assertNull(rejection.newMemoryStr());
        assertTrue(replacement.save());
        assertEquals("reviewed text", replacement.newMemoryStr());
        assertTrue(clearing.save());
        assertEquals("", clearing.newMemoryStr());
        assertDoesNotThrow(() -> new MemoryDecision("replacement content", true).requireValid());
        assertThrows(IllegalArgumentException.class, () -> new MemoryDecision("replacement", false));
        assertThrows(IllegalArgumentException.class, () -> new MemoryDecision("", false));
    }

    @Test
    void preservesAccessContextWithoutSerializingLiveWorkspace() throws Exception {
        Workspace workspace = new Workspace() {
            @Override
            public String id() { return "workspace-1"; }

            @Override
            public RuntimeEnvironment runtimeEnvironment() { return null; }

            @Override
            public String workDir() { return "work"; }

            @Override
            public Path resolve(String path) { return Path.of("work").resolve(path); }
        };
        MemoryContext context = MemoryContext.builder().executionId("execution-1")
                .memoryConfig(MemoryConfig.builder().credential("project-memory")
                        .mode(MemoryManagerMode.READ_ONLY).build())
                .workspace(workspace).attributes(Map.of("principal", "user-1", "tenant", "tenant-1"))
                .query("Project conventions").build();

        String json = mapper.writeValueAsString(context);
        MemoryContext restored = mapper.readValue(json, MemoryContext.class);

        assertFalse(mapper.readTree(json).has("workspace"));
        assertNull(restored.getWorkspace());
        assertEquals(context.getExecutionId(), restored.getExecutionId());
        assertEquals(context.getMemoryConfig(), restored.getMemoryConfig());
        assertEquals(context.getAttributes(), restored.getAttributes());
        assertEquals(context.getQuery(), restored.getQuery());
        assertFalse(restored.isAllowOutsideWorkspace());
    }

    private <T> T roundTrip(T value, Class<T> type) throws Exception {
        return mapper.readValue(mapper.writeValueAsString(value), type);
    }
}
