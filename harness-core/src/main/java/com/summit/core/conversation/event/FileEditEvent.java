package com.summit.core.conversation.event;

import lombok.Builder;
import lombok.Data;

import java.io.Serializable;

@Builder
@Data
public class FileEditEvent {
    private Object recordId;
    /** Id of the agent request (turn) this edit belongs to. */
    private String turnId;
    private String filePath;
    private String oldContent;
    private String newContent;
    /** Unified-diff added/removed line counts for the "+N -M" chip. */
    private Integer plusLines;
    private Integer minusLines;
    Serializable sessionId;
}
