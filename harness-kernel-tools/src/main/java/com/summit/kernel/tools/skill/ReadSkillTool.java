package com.summit.kernel.tools.skill;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.summit.core.tool.ToolExecuteResult;
import com.summit.core.tool.ToolExecution;
import com.summit.core.tool.ToolExecutor;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Files;
import java.nio.file.Path;

@RequiredArgsConstructor
@Slf4j
public class ReadSkillTool implements ToolExecutor {
    public static final String NAME = "read_skill";
    private final ObjectMapper objectMapper;

    @Override
    public boolean requiresWorkspace() {
        return false;
    }
    @Override
    public @NonNull ToolExecuteResult execute(ToolExecution toolExecution) {
        try {
            String args = toolExecution.getArgs();
            ReadSkillToolArgument argument = objectMapper.readValue(args, ReadSkillToolArgument.class);
            if (argument == null) return ToolExecuteResult.err("Skill arguments must be an object");

            String pathStr = argument.getPath();

            if(pathStr == null || pathStr.isBlank())return ToolExecuteResult.err("Path is null or empty");

            if (toolExecution.getSkillConfig() == null || toolExecution.getSkillConfig().getPath() == null) {
                return ToolExecuteResult.err("No Skill directory configured for this request");
            }

            Path root = toolExecution.getSkillConfig().getPath().toRealPath();

            if (!Files.isDirectory(root)) return ToolExecuteResult.err("Skill root is not a directory");

            Path requested = Path.of(pathStr);

            Path file = (requested.isAbsolute() ? requested : root.resolve(requested)).normalize();

            file = file.toRealPath();

            if (!file.startsWith(root)) return ToolExecuteResult.err("Path is outside the configured Skill directory");

            if (!Files.isRegularFile(file)) return ToolExecuteResult.err("Path is not a file");

            String content = Files.readString(file);

            return ToolExecuteResult.success(String.format("""
                    name: %s
                    path: %s
                    resourceDirectory: %s
                    content:
                    %s
                    """,argument.getName() == null ? file.getFileName() : argument.getName(),
                    file, file.getParent(), content));

        }catch (Exception e){
            return ToolExecuteResult.err("Failed to read skill:"+e.getMessage());
        }
    }
}
