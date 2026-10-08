package com.summit.runtime.skill;
import com.summit.core.skill.SkillResolver;
import com.summit.core.skill.SkillResume;
import lombok.extern.slf4j.Slf4j;
import org.commonmark.ext.front.matter.YamlFrontMatterExtension;
import org.commonmark.ext.front.matter.YamlFrontMatterVisitor;
import org.commonmark.parser.Parser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;



@Slf4j
public class DefaultSkillResolver implements SkillResolver {
    @Override
    public SkillResume resolve(Path skillMD) {
        String fileContent;
        try {
            fileContent = Files.readString(skillMD);
            if(fileContent.isEmpty())return null;
        } catch (IOException e) {
            log.error("Error reading file: {}", skillMD, e);
            throw new RuntimeException(e);
        }
        Parser parser = Parser.builder()
                .extensions(List.of(YamlFrontMatterExtension.create()))
                .build();

        YamlFrontMatterVisitor visitor = new YamlFrontMatterVisitor();
        parser.parse(fileContent).accept(visitor);

        Map<String, List<String>> data = visitor.getData();
        String description=null, name=null;

        List<String> nameList = data.get("name");
        if (nameList != null && !nameList.isEmpty()) {
            name = nameList.getFirst();
        }

        List<String> descriptionList = data.get("description");
        if (descriptionList != null && !descriptionList.isEmpty()){
            description = descriptionList.getFirst();
        }
        if(name ==null || name.isEmpty() )return null;

        return new SkillResume(name, description,skillMD.toAbsolutePath().toString());
    }



}
