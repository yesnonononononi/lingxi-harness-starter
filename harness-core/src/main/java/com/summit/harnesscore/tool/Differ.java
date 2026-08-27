package com.summit.harnesscore.tool;

import java.nio.file.Path;

public interface Differ {
      DiffResult diff(Path path, String newContent);
      DiffResult diff(String fileName,String oldContent, String newContent);
}
