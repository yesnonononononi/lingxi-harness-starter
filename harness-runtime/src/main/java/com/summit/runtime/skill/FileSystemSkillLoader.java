package com.summit.runtime.skill;

import com.summit.core.conf.SkillConfig;
import com.summit.core.skill.SkillLoader;
import com.summit.core.skill.SkillResolver;
import com.summit.core.skill.SkillResume;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * the skill loader for file system.It is valid in the local environment.
 * <p>You <strong>must</strong> manually implement the {@link SkillLoader}</p> if you want to deploy the system in the production environment
 */
@Slf4j
@RequiredArgsConstructor
public class FileSystemSkillLoader implements SkillLoader {
    private final SkillResolver skillResolver;
    private final String SKILL_MD = "SKILL.md";

    @Override
    public SkillResolver provideSkillResolver() {
        return skillResolver;
    }

    @Override
    public List<SkillResume> load(SkillConfig skillConfig) {
        if(skillResolver == null) {
            throw new IllegalStateException("No SkillResolver available; add CommonMark or provide a custom SkillResolver/SkillLoader");
        }
        Path path = skillConfig.getPath();
        ensurePathIsValid(path);

        try(Stream<Path> stream = Files.find(path, Integer.MAX_VALUE, (filePath, attrs) -> attrs.isRegularFile()
                && filePath.getFileName().toString().equals(SKILL_MD))){

            List<Path> skills = stream.toList();
            if(skills.isEmpty())return List.of();

            return skills.stream().map(skillResolver::resolve)
                    .filter(Objects::nonNull)
                    .toList();

        }catch (Exception e){
            throw new RuntimeException(e);
        }
    }



    private void ensurePathIsValid(Path path){
        if(path == null)throw new IllegalArgumentException("The path cannot be null");

        if (!Files.exists(path)) {
            throw new IllegalArgumentException("The path " + path + " does not exist");
        }

        if (!Files.isDirectory(path)) {
            throw new IllegalArgumentException("The path " + path + " is not a directory");
        }


    }
}
