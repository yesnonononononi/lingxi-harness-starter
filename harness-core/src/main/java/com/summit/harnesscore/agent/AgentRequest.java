package com.summit.harnesscore.agent;


import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class AgentRequest {
    private String input;
}
