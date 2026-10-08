package com.summit.core.conversation.api;


import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Builder;

/**
 * One tool call requested by the model. Structured per-call extras (display metadata, approval
 * payloads) do not belong here: executors attach them to {@code ToolExecuteResult.toolMetaData}.
 *
 * <p>{@code intention} is retired: snapshots written before its removal still carry the key, so it
 * is ignored by name instead of failing snapshot restore.</p>
 */
@Builder
@JsonIgnoreProperties("intention")
public record ToolCallRequest(String id, String name, String arguments) {

}
