package com.summit.harnesscore.conversation.api;


import lombok.Builder;

@Builder
public record ToolCallRequest(String id,String name,String arguments) {

}
