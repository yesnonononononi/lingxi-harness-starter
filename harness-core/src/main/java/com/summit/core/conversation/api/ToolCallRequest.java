package com.summit.core.conversation.api;



import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Builder;

/**
 * One tool call requested by the model. Structured per-call extras (display metadata, approval
 * payloads) do not belong here: executors attach them to {@code ToolExecuteResult.toolMetaData}.
 *
 * <p>Snapshots written before {@code intention} was retired can still carry the field.</p>
 *
 * @param requestIndex zero-based position in the model response's original tool-call list
 */
@Builder(toBuilder = true)
@JsonIgnoreProperties("intention")
public record ToolCallRequest(String id, String name,int requestIndex, String arguments) {
    public ToolCallRequest withArguments(String newArgv){
        return this.toBuilder().arguments(newArgv).build();
    }
}
