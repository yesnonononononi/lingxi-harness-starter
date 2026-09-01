package com.summit.core.compact;

import lombok.Builder;

@Builder
public record ContextSqueezeRequest(boolean shouldSqueeze,Integer expectTokens) {

}
