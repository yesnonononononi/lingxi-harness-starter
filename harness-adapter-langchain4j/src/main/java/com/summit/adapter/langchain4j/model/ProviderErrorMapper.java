package com.summit.adapter.langchain4j.model;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.summit.core.exception.ModelException;
import com.summit.core.exception.NonRetriableModelException;
import com.summit.core.exception.RetriableModelException;
import dev.langchain4j.exception.RetriableException;
import dev.langchain4j.model.anthropic.internal.api.AnthropicStreamingException;

import java.util.Set;

/** Recovers explicit provider error codes that the SDK streaming callbacks leave unclassified. */
final class ProviderErrorMapper {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> PERMANENT = Set.of("insufficient_quota", "billing_hard_limit_reached",
            "organization_usage_limit_exceeded", "usage_limit_exceeded", "billing_error", "context_length_exceeded",
            "invalid_request_error", "authentication_error", "permission_error", "not_found_error", "request_too_large");
    private static final Set<String> TRANSIENT = Set.of("api_error", "overloaded_error", "timeout_error",
            "server_error", "internal_server_error", "service_unavailable_error", "server_is_overloaded",
            "rate_limit_exceeded", "rate_limit_error");

    private ProviderErrorMapper() { }

    static ModelException map(Throwable classified, Throwable original) {
        if (classified instanceof AnthropicStreamingException streaming) {
            return classify(streaming.type(), "", streaming.getMessage(), original);
        }
        if (classified.getClass() != RuntimeException.class && !(classified instanceof RetriableException)) return null;
        String message = classified.getMessage();
        if (message == null) return null;
        int start = message.indexOf('{');
        if (start < 0) return null;
        try {
            JsonNode root = JSON.readTree(message.substring(start));
            JsonNode error = root.has("error") ? root.path("error") : root;
            return classify(error.path("type").asText(), error.path("code").asText(),
                    error.path("message").asText(message), original);
        } catch (JsonProcessingException ignored) {
            return null;
        }
    }

    private static ModelException classify(String type, String code, String message, Throwable original) {
        if (type == null) return null;
        if (PERMANENT.contains(code) || PERMANENT.contains(type)) return new NonRetriableModelException(message, original);
        if (TRANSIENT.contains(code) || TRANSIENT.contains(type)) return new RetriableModelException(message, original);
        return null;
    }
}
