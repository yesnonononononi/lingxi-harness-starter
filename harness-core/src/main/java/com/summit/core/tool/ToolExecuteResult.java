package com.summit.core.tool;


import com.summit.core.conversation.event.AgentEvent;
import lombok.Builder;
import lombok.Data;

import java.util.Map;
import java.util.Objects;


@Builder
@Data
public class ToolExecuteResult {
    /** Framework-level outcome: {@code 1} = the framework ran the tool, {@code 0} = it failed. */
    private Integer code;

    /**
     * The tool's own exit status, when it has one (e.g. a shell command's {@code $?}), or
     * {@code null} when the tool has no such notion.
     *
     * <p>Deliberately separate from {@link #code}: {@code code} answers "did the framework execute
     * the tool", while a process exit status answers "what did the tool report", and the two invert
     * — a shell exit status of {@code 0} means success while {@link #isSuccess()} is true only for
     * {@code code == 1}. Folding them together would flip every successful command to failed.</p>
     */
    private Integer exitCode;


    private String id;

    // tool request index
    private int requestIndex;

    private ToolDefinition<?> toolSpecification;

    private ToolResultType toolResultType;

    private String toolOutput;

    /**
     * the field allows the user to carry some extra information to the {@link AgentEvent}
     */
    private Map<String, Object> toolMetaData;

    public static ToolExecuteResult success(String toolOutput) {
        return ToolExecuteResult.builder()
                .code(1)
                .toolResultType(ToolResultType.NORMAL)
                .toolOutput(Objects.requireNonNullElse(toolOutput, ""))
                .build();
    }

    public static ToolExecuteResult success(String output, ToolResultType toolResultType, Map<String, Object> toolMetaData) {
        return ToolExecuteResult.builder()
                .code(1)
                .toolResultType(toolResultType)
                .toolOutput(Objects.requireNonNullElse(output, ""))
                .toolMetaData(toolMetaData)
                .build();
    }


    public static ToolExecuteResult success(String toolOutput, ToolResultType toolResultType) {
        return ToolExecuteResult.builder()
                .code(1)
                .toolResultType(toolResultType)
                .toolOutput(Objects.requireNonNullElse(toolOutput, ""))
                .build();
    }


    /**
     * Returns a successful placeholder result that asks the loop to commit this tool round and
     * suspend. No thread or future is retained by the framework.
     */
    public static ToolExecuteResult promise(String toolOutput) {
        return success(toolOutput, ToolResultType.PROMISE);
    }

    public static ToolExecuteResult promise(String toolOutput, Map<String, Object> toolMetaData) {
        return success(toolOutput, ToolResultType.PROMISE, toolMetaData);
    }

    public static ToolExecuteResult err(String toolOutput) {
        return err(toolOutput, ToolResultType.NORMAL, null);
    }


    public static ToolExecuteResult err(String toolOutput, ToolResultType toolResultType) {
        return err(toolOutput, toolResultType, null);
    }

    public static ToolExecuteResult err(String toolOutput, ToolResultType toolResultType, Map<String, Object> toolMetaData) {
        return ToolExecuteResult.builder()
                .code(0)
                .toolResultType(toolResultType)
                .toolMetaData(toolMetaData)
                .toolOutput(Objects.requireNonNullElse(toolOutput, ""))
                .build();
    }



    public boolean isSuccess() {
        return Integer.valueOf(1).equals(this.code);
    }

    public boolean isPromise() {
        return this.toolResultType == ToolResultType.PROMISE;
    }
}
