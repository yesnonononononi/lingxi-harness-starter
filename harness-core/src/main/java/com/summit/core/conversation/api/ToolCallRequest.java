package com.summit.core.conversation.api;


import lombok.Builder;

@Builder
public record ToolCallRequest(String id,String name,String arguments) {

}
