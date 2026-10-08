package com.summit.core.conf;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.nio.file.Path;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class SkillConfig {
    /** Host-side resource root used by the default filesystem implementations. */
    private Path path;
}
