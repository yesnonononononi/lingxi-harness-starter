package com.summit.harnesscore.agent;

import lombok.Builder;
import lombok.Data;
import lombok.ToString;

@ToString
@Builder
@Data
public class ExecutionResult {
    private String text;
    private String thinking;
}
