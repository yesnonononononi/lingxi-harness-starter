package com.summit.harnesscore.compact;

import lombok.Builder;

@Builder
public record ContextSqueezeRequest(boolean shouldSqueeze,Integer expectTokens) {

}
