package com.summit.harnesscore.tool;


public interface Tool {
   String name();
   String id();
   ToolExecutor executor();
}
