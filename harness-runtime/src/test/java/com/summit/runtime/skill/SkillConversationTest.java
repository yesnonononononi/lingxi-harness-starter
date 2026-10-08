package com.summit.runtime.skill;

import com.summit.core.agent.AgentRequest;
import com.summit.core.agent.Execution;
import com.summit.core.compact.ContextAttachmentProvider;
import com.summit.core.conf.SkillConfig;
import com.summit.core.conversation.message.UserMessageEntity;
import com.summit.core.mcp.McpToolScope;
import com.summit.runtime.conversation.DefaultConversationManager;
import com.summit.runtime.prompt.SystemPromptAssembler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SkillConversationTest {
    @TempDir Path root;

    @Test
    void promptContainsLoadInstructionsAndEntryPathWhileIgnoringInvalidEntry() throws Exception {
        Path entry = root.resolve("review/SKILL.md");
        Files.createDirectories(entry.getParent());
        Files.writeString(entry, "---\nname: review\ndescription: Review source\n---\nRead references/check.md");
        Path invalid = root.resolve("invalid/SKILL.md");
        Files.createDirectories(invalid.getParent());
        Files.writeString(invalid, "# No metadata");
        String prompt = start(new FileSystemSkillLoader(new DefaultSkillResolver()), new SkillConfig(root));
        assertTrue(prompt.contains("review: Review source"));
        assertTrue(prompt.contains(entry.toAbsolutePath().toString()));
        assertTrue(prompt.contains("`read_skill`"));
        assertFalse(prompt.contains("No metadata"));
    }

    @Test
    void invalidConfiguredDirectoryIsAnErrorRatherThanAnEmptyCatalog() {
        assertThrows(IllegalArgumentException.class, () -> start(
                new FileSystemSkillLoader(new DefaultSkillResolver()), new SkillConfig(root.resolve("absent"))));
    }

    @Test
    void missingParserIsAllowedForPlainRequestsButReportedForSkillRequests() {
        FileSystemSkillLoader loader = new FileSystemSkillLoader(null);
        assertFalse(start(loader, null).contains("Skill Prompt"));
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> start(loader, new SkillConfig(root)));
        assertTrue(failure.getMessage().contains("SkillResolver"));
    }

    private String start(FileSystemSkillLoader loader, SkillConfig config) {
        DefaultConversationManager conversations = new DefaultConversationManager(null,
                ContextAttachmentProvider.NONE, SystemPromptAssembler::new, loader);
        AgentRequest request = AgentRequest.builder().skillConfig(config)
                .messages(List.of(UserMessageEntity.from("review"))).build();
        Execution execution = Execution.create(request, "test");
        conversations.startConversation(execution, null, McpToolScope.EMPTY);
        return execution.getMessages().getFirst().text();
    }
}
