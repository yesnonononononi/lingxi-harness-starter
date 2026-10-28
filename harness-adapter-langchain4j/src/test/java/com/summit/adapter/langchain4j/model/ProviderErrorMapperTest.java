package com.summit.adapter.langchain4j.model;

import com.summit.core.exception.NonRetriableModelException;
import com.summit.core.exception.RetriableModelException;
import dev.langchain4j.exception.RateLimitException;
import dev.langchain4j.model.anthropic.internal.api.AnthropicStreamingException;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.*;

class ProviderErrorMapperTest {
    @Test
    void streamingOverloadAndTimeoutCodesAreRetriableWithoutGuessingUnknownErrors() {
        RuntimeException openai = new RuntimeException("{\"error\":{\"type\":\"server_error\",\"message\":\"temporary\"}}");
        assertSame(openai, assertInstanceOf(RetriableModelException.class, ModelExceptionMapper.map(openai)).getCause());
        AnthropicStreamingException anthropic = new AnthropicStreamingException("busy", "overloaded_error");
        assertSame(anthropic, assertInstanceOf(RetriableModelException.class, ModelExceptionMapper.map(anthropic)).getCause());
        assertInstanceOf(RetriableModelException.class,
                ModelExceptionMapper.map(new AnthropicStreamingException("slow", "timeout_error")));
        RuntimeException unknown = new RuntimeException("{\"error\":{\"type\":\"unrecognized\"}}");
        assertSame(unknown, ModelExceptionMapper.map(unknown));
        RuntimeException malformed = new RuntimeException("{invalid");
        assertSame(malformed, ModelExceptionMapper.map(malformed));
    }

    @Test
    void quotaCodeOverridesARetriableHttp429Classification() {
        RateLimitException quota = new RateLimitException("{\"error\":{\"type\":\"insufficient_quota\",\"code\":\"insufficient_quota\"}}");
        assertSame(quota, assertInstanceOf(NonRetriableModelException.class, ModelExceptionMapper.map(quota)).getCause());
        RateLimitException limited = new RateLimitException("{\"error\":{\"type\":\"rate_limit_error\"}}");
        assertInstanceOf(RetriableModelException.class, ModelExceptionMapper.map(limited));
        assertInstanceOf(NonRetriableModelException.class,
                ModelExceptionMapper.map(new CompletionException(new AnthropicStreamingException("billing", "billing_error"))));
    }
}
