package com.summit.harnesscore.runtime;


import java.nio.file.Path;

public interface Workspace {
    RuntimeEnvironment runTimeEnvironment();


     Path resolve(String path);


}
