package com.summit.harnesscore.conversation.event;

import lombok.Builder;
import lombok.Data;

import java.io.Serializable;

@Builder
@Data
public class FileEditEvent {
    private Object patchId;
    private String filePath;
    private String oldContent;
    private String newContent;
    Serializable sessionId;
}
