package com.summit.runtime.skill;

import com.summit.core.skill.SkillLoader;
import lombok.RequiredArgsConstructor;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * the skill loader for file system.It is valid in the local environment.
 * <p>You <strong>must</strong> manually implement the {@link SkillLoader}</p> if you want to deploy the system in the production environment
 */
@RequiredArgsConstructor
public class FileSystemSkillLoader implements SkillLoader {
    private final String envName; // example for --- .\lingxi
    // System.getProperty(user.dir)\.lingxi\skills\skillName\skill.md
    @Override
    public String loadByName(String name) {
        try {
            Path skillPath = Path.of(System.getProperty("user.dir"), envName, "skills", name, "SKILL.md");

            File skillMD = skillPath.toFile();

            if (!skillMD.isFile() || !skillMD.exists()) return "the skill does not exist";

            return Files.readString(skillPath);
        }catch (Exception e){
            return "Error at loading skill: " + e.getMessage();
        }
    }


}
