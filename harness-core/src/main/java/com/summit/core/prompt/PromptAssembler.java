package com.summit.core.prompt;

import com.summit.core.mcp.McpResume;
import com.summit.core.runtime.workspace.Workspace;
import com.summit.core.skill.SkillResume;

import java.util.List;

public interface PromptAssembler {
    PromptAssembler startWithWorkspace(Workspace workspace);
    PromptAssembler withBusinessPrompt(String businessPrompt);
    PromptAssembler withTaskPrompt(List<String> task);
    PromptAssembler withMcpToolPrompt(List<McpResume> mcpTools);
    PromptAssembler withMemoryPrompt(List<String> memory);
    PromptAssembler withSkillPrompt(List<SkillResume> skills);
    String complete();
}
