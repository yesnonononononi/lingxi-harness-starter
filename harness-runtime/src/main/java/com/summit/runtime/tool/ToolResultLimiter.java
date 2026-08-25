package com.summit.runtime.tool;

import com.summit.harnesscore.compact.Tokenizer;
import com.summit.harnesscore.tool.ToolDefinition;
import com.summit.harnesscore.tool.ToolExecuteResult;

public class ToolResultLimiter {

    public static ToolExecuteResult limit(ToolExecuteResult result, ToolDefinition<?> toolDefinition, Tokenizer tokenizer) {
            result.setToolOutput(limitOutput(result.getToolOutput(), toolDefinition.maxOutput(), tokenizer));
            return result;
    }

    private static String limitOutput(String output, int maxOutput , Tokenizer tokenizer) {
        if (output == null) return null;
        return tokenizer.truncate(output, maxOutput);
    }
}
