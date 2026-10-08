package com.summit.kernel.tools.skill;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.summit.core.conf.SkillConfig;
import com.summit.core.conversation.api.ToolCallRequest;
import com.summit.core.conversation.event.RuntimeEventPublisher;
import com.summit.core.tool.*;
import com.summit.runtime.tool.DefaultToolExecutionManager;
import com.summit.runtime.tool.DefaultToolInterceptor;
import com.summit.runtime.conversation.DefaultTokenizer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ReadSkillToolTest {
    @TempDir Path directory;
    private final ObjectMapper mapper = new ObjectMapper();
    private final ReadSkillTool tool = new ReadSkillTool(mapper);

    @Test
    void readsEntryAndReferencedResourceWithoutWorkspace() throws Exception {
        Path root = Files.createDirectories(directory.resolve("skills"));
        Path entry = root.resolve("review/SKILL.md");
        Files.createDirectories(entry.getParent());
        Files.writeString(entry, "Read references/check.md");
        Path reference = entry.getParent().resolve("references/check.md");
        Files.createDirectories(reference.getParent());
        Files.writeString(reference, "check source correctness");

        ToolExecuteResult entryResult = read(root, entry.toString());
        assertTrue(entryResult.isSuccess());
        assertTrue(entryResult.getToolOutput().contains("Read references/check.md"));
        assertTrue(entryResult.getToolOutput().contains(entry.getParent().toRealPath().toString()));
        ToolExecuteResult referenceResult = read(root, "review/references/check.md");
        assertTrue(referenceResult.isSuccess());
        assertTrue(referenceResult.getToolOutput().contains("check source correctness"));
    }

    @Test
    void rejectsUnconfiguredRootAndOtherRequestsResources() throws Exception {
        Path first = Files.createDirectories(directory.resolve("first"));
        Path second = Files.createDirectories(directory.resolve("second"));
        Path secret = second.resolve("SKILL.md");
        Files.writeString(secret, "other request content");
        ToolExecuteResult result = read(first, secret.toString());
        assertFalse(result.isSuccess());
        assertFalse(result.getToolOutput().contains("other request content"));
        assertFalse(read(first, "../second/SKILL.md").isSuccess());
        assertFalse(read(null, secret.toString()).isSuccess());
    }

    @Test
    void rejectsSymbolicLinksOutsideRoot() throws Exception {
        Path root = Files.createDirectories(directory.resolve("skills"));
        Path outside = directory.resolve("outside.md");
        Files.writeString(outside, "outside content");
        Path link = root.resolve("link.md");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (IOException | UnsupportedOperationException | SecurityException failure) {
            assumeTrue(false, "Symbolic links unavailable: " + failure.getMessage());
        }
        assertFalse(read(root, link.toString()).isSuccess());
    }

    @Test
    void passesRootThroughRuntimeAndAppliesOutputLimitAndWhitelist() throws Exception {
        Path root = Files.createDirectories(directory.resolve("skills"));
        Files.writeString(root.resolve("SKILL.md"), "long resource content ".repeat(100));
        ReadSkillToolProperties properties = new ReadSkillToolProperties();
        properties.setMaxOutput(80);
        ToolDefinition<ReadSkillTool> definition = new ReadSkillToolAutoConfiguration()
                .readSkillToolDefinition(tool, properties);
        ToolExecutionContext context = ToolExecutionContext.builder()
                .toolRegistry(new ToolRegistry(List.of(definition)))
                .runtimeEventPublisher(new RuntimeEventPublisher(List.of())).build();
        DefaultToolInterceptor outputLimiter = new DefaultToolInterceptor(new DefaultTokenizer());
        try (DefaultToolExecutionManager manager = new DefaultToolExecutionManager(context,
                invocation -> {
                    Object result = invocation.getMethod().invoke(invocation.getTarget(), invocation.getContext());
                    outputLimiter.after(invocation, result);
                    return result;
                }, List.of())) {
            ToolExecuteCommand command = command(root, List.of(ReadSkillTool.NAME));
            ToolExecuteResult result = manager.execute(command).getFirst();
            assertTrue(result.isSuccess(), result.getToolOutput());
            assertTrue(result.getToolOutput().contains("[OUTPUT_TRUNCATED]"), result.getToolOutput());
            assertTrue(result.getToolOutput().length() < 400, result.getToolOutput());
            assertFalse(manager.execute(command(root, List.of())).getFirst().isSuccess());
        }
    }

    @Test
    void malformedArgumentsAndMissingFilesReturnErrors() throws Exception {
        Path root = Files.createDirectories(directory.resolve("skills"));
        for (String args : List.of("null", "{}", "{", "{\"path\":\"   \"}")) {
            assertFalse(tool.execute(ToolExecution.builder().executionId("test")
                    .skillConfig(new SkillConfig(root)).args(args).build()).isSuccess());
        }
        assertFalse(read(root, "absent.md").isSuccess());
        assertFalse(read(root, ".").isSuccess());
    }

    private ToolExecuteResult read(Path root, String path) throws Exception {
        return tool.execute(ToolExecution.builder().executionId("test")
                .skillConfig(root == null ? null : new SkillConfig(root))
                .args(mapper.writeValueAsString(Map.of("path", path))).build());
    }

    private ToolExecuteCommand command(Path root, List<String> allowed) {
        return new ToolExecuteCommand(List.of(new ToolCallRequest("call", ReadSkillTool.NAME,
                "{\"path\":\"SKILL.md\"}")), "test", null, Map.of(), Map.of(), allowed,
                UUID.randomUUID(), false, null, new SkillConfig(root));
    }
}
