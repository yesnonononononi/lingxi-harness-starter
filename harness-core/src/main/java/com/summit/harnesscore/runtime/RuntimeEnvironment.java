package com.summit.harnesscore.runtime;

import lombok.Builder;


import java.nio.charset.Charset;
import java.util.Map;
@Builder
public record RuntimeEnvironment(String workDir, Charset charset, Map<String,String> envs, OsType osType,ShellType shellType) {
}
