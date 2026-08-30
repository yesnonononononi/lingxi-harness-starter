package com.summit.core.tool;

import java.util.List;

public interface ToolExecutionManager {
        List<ToolExecuteResult> execute(ToolExecuteCommand toolExecuteCommand );
        ToolRegistry toolRegistry();
    }

