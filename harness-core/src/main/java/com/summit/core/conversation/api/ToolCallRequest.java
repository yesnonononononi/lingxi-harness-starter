package com.summit.core.conversation.api;



import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Builder;

/**
 * One tool call requested by the model. Structured per-call extras (display metadata, approval
 * payloads) do not belong here: executors attach them to {@code ToolExecuteResult.toolMetaData}.
 *
 * <p>{@code intention} is retired: snapshots written before its removal still carry the key, so it
 * is ignored by name instead of failing snapshot restore.</p>
 *
 * @param requestIndex zero-based position in the model response's original tool-call list
 */
@Builder
@JsonIgnoreProperties("intention")
public record ToolCallRequest(String id, String name,int requestIndex, String arguments) {

}
