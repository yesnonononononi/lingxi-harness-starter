package com.summit.core.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.summit.core.conversation.api.ToolCallRequest;
import com.summit.core.tool.ToolDefinition;
import com.summit.core.tool.ToolRegistry;

import java.util.List;
import java.util.Objects;

/**
 * The single model-facing entry through which every remote (MCP) tool is called.
 *
 * <p><b>Why a gateway.</b> Declaring a remote tool only once the model has looked it up changes the
 * tool list in the middle of a run, and the tool list sits at the head of the prompt: every lookup
 * invalidated the provider's prefix cache and the signatures bound to it. With the gateway the tool
 * list is the static tools plus this one entry for the whole run; a remote tool's schema reaches the
 * model only as the result of a lookup, behind the cached prefix.</p>
 *
 * <p><b>Boundary normalization, not an executor.</b> A gateway call is rewritten at the execution
 * entry into the call of the tool it names — same call id, same request index, the inner arguments
 * as the argument string — and from there takes exactly the path a direct call takes: resolution,
 * admission, concurrency, timeout, output truncation and tool-call events all see the real tool.
 * The model's own transcript keeps the gateway call; runtime events, audit and results carry the
 * real tool name. Anything that counts tools "as the model reported them" will therefore disagree
 * with the runtime events by design.</p>
 *
 * <p>Only a tool of the request's own {@link McpToolScope} can be named: a built-in is never reached
 * through the gateway, which keeps the gateway from turning into a way around the static
 * whitelist.</p>
 */
public final class McpToolGateway {

    /** Model-facing name of the gateway; outside the {@code mcp_} namespace remote tools are published in. */
    public static final String NAME = "call_mcp_tool";

    /**
     * Name of the lookup entry that hands the model a remote tool's schema. The gateway is the call
     * half of that lookup, so a run whose whitelist admits the lookup also sees the gateway.
     */
    public static final String DISCOVERY_TOOL = "search_tool";

    /** Field of the gateway arguments naming the remote tool. */
    public static final String TOOL_NAME_FIELD = "name";

    /** Field of the gateway arguments carrying the remote tool's own arguments. */
    public static final String ARGUMENTS_FIELD = "arguments";

    private static final ObjectMapper JSON = new ObjectMapper();

    private McpToolGateway() {
    }

    /** Outcome of resolving one gateway call's arguments. */
    public sealed interface Resolution permits Target, Rejected {
    }

    /** The remote tool a gateway call stands for, with the argument string its executor parses. */
    public record Target(String name, String arguments) implements Resolution {
    }

    /** Why a gateway call names no callable remote tool; written for the model to correct itself. */
    public record Rejected(String reason) implements Resolution {
    }

    /** Whether the given request whitelist exposes the gateway: it names the gateway or the lookup entry. */
    public static boolean admittedBy(List<String> whitelist) {
        return whitelist != null && whitelist.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .anyMatch(name -> NAME.equals(name) || DISCOVERY_TOOL.equals(name));
    }

    public static boolean isGateway(String toolName) {
        return NAME.equals(toolName);
    }

    /**
     * The call a gateway call stands for, or the call itself when it is not a gateway call or names
     * nothing callable — the latter then resolves to the gateway definition, whose executor reports
     * the reason.
     */
    public static ToolCallRequest unwrap(ToolCallRequest call, McpToolScope scope, ToolRegistry registry) {
        if (call == null || !isGateway(call.name())) {
            return call;
        }
        return resolve(call.arguments(), scope, registry) instanceof Target target
                ? call.toBuilder().name(target.name()).arguments(target.arguments()).build()
                : call;
    }

    /**
     * Resolves the arguments of a gateway call against the request's scope. The static registry is
     * consulted only to refuse its names: a built-in must be called directly, never through the
     * gateway.
     *
     * @param argumentsJson the gateway call's arguments, {@code {"name": ..., "arguments": ...}}
     * @param scope         the MCP tools of the request, may be null
     * @param registry      the static tools of the process, may be null
     */
    public static Resolution resolve(String argumentsJson, McpToolScope scope, ToolRegistry registry) {
        McpToolScope tools = scope == null ? McpToolScope.EMPTY : scope;

        JsonNode envelope = readObject(argumentsJson);
        if (envelope == null) {
            return new Rejected(NAME + " expects a JSON object {\"" + TOOL_NAME_FIELD + "\": <remote tool name>, \""
                    + ARGUMENTS_FIELD + "\": {...}}");
        }
        JsonNode nameNode = envelope.get(TOOL_NAME_FIELD);
        String name = nameNode == null || !nameNode.isTextual() ? "" : nameNode.asText().strip();
        if (name.isEmpty()) {
            return new Rejected(NAME + " requires \"" + TOOL_NAME_FIELD
                    + "\": the exact remote tool name returned by " + DISCOVERY_TOOL);
        }
        if (isGateway(name)) {
            return new Rejected(NAME + " cannot call itself");
        }
        if (registry != null && registry.getTool(name) != null) {
            return new Rejected("'" + name + "' is a built-in tool: call it directly, not through " + NAME);
        }
        ToolDefinition<?> target = tools.getTool(name);
        if (target == null) {
            return new Rejected("unknown MCP tool '" + name + "': use " + DISCOVERY_TOOL
                    + " to find the exact name of a remote tool connected to this run");
        }

        String arguments = argumentsOf(envelope.get(ARGUMENTS_FIELD));
        if (arguments == null) {
            return new Rejected("\"" + ARGUMENTS_FIELD + "\" of " + NAME + " must be a JSON object matching the"
                    + " parameter schema of '" + name + "': "
                    + Objects.requireNonNullElse(target.parametersJsonSchema(), "{}"));
        }
        return new Target(name, arguments);
    }

    /**
     * The remote tool's arguments as the string its executor parses. A missing value means no
     * arguments; a string holding a JSON object — models double-encode now and then — is accepted
     * as that object. Anything else is not an argument object.
     */
    private static String argumentsOf(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return "{}";
        }
        if (node.isTextual()) {
            String text = node.asText();
            if (text.isBlank()) {
                return "{}";
            }
            JsonNode parsed = readObject(text);
            return parsed == null ? null : write(parsed);
        }
        return node.isObject() ? write(node) : null;
    }

    private static JsonNode readObject(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            JsonNode node = JSON.readTree(text);
            return node != null && node.isObject() ? node : null;
        } catch (JsonProcessingException malformed) {
            return null;
        }
    }

    private static String write(JsonNode node) {
        try {
            return JSON.writeValueAsString(node);
        } catch (JsonProcessingException impossible) {
            throw new IllegalStateException("A parsed JSON tree must serialize", impossible);
        }
    }
}
