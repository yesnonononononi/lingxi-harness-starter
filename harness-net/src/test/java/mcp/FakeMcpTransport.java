package mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.ProtocolVersions;
import mcp.json.Jackson2McpJsonMapper;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * In-memory transport that speaks enough of the protocol to drive a real client without a server.
 *
 * <p>It exists because the SDK's client classes are concrete: the only honest way to exercise a
 * {@code McpSyncClient} in a unit test is to give it a transport. Every answer is a plain
 * {@link Map} that the client deserializes through {@link #unmarshalFrom}, exactly like the HTTP
 * transport does with a response body.</p>
 *
 * <p>Failure switches ({@link #failPings}, {@link #failToolList}, {@link #failInitialize}) are the
 * knobs the health-check and discovery tests need; there is no attempt to model a real server.</p>
 */
final class FakeMcpTransport implements McpClientTransport {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final McpJsonMapper jsonMapper = new Jackson2McpJsonMapper(objectMapper);
    private final List<String> receivedMethods = Collections.synchronizedList(new ArrayList<>());
    private final List<Map<String, Object>> receivedToolCalls = Collections.synchronizedList(new ArrayList<>());
    private final AtomicInteger closeCount = new AtomicInteger();

    private volatile Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>> handler;
    private volatile boolean closed;
    private volatile boolean failPings;
    private volatile boolean failToolList;
    private volatile boolean failInitialize;
    private volatile String protocolVersionOverride;
    private volatile String instructions = "fake server instructions";
    private volatile Map<String, Object> toolCallResult =
            Map.of("content", List.of(Map.of("type", "text", "text", "ok")), "isError", false);
    private volatile List<Map<String, Object>> tools = List.of(
            Map.of("name", "search", "description", "Search things",
                    "inputSchema", Map.of("type", "object",
                            "properties", Map.of("q", Map.of("type", "string")),
                            "required", List.of("q"))));

    @Override
    public Mono<Void> connect(
            Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>> inboundHandler) {
        if (closed) {
            throw new IllegalStateException("MCP stdio session is closed");
        }
        this.handler = inboundHandler;
        return Mono.empty();
    }

    @Override
    public Mono<Void> sendMessage(McpSchema.JSONRPCMessage message) {
        if (closed) {
            return Mono.error(new IllegalStateException("transport is closed"));
        }
        if (!(message instanceof McpSchema.JSONRPCRequest request)) {
            // Notifications (initialized, cancelled, ...) expect no answer.
            receivedMethods.add("notification:" + requestMethod(message));
            return Mono.empty();
        }
        receivedMethods.add(request.method());
        if (request.method().equals(McpSchema.METHOD_TOOLS_CALL)) {
            recordToolCall(request.params());
        }
        Object result;
        try {
            result = respond(request);
        } catch (RuntimeException failure) {
            return Mono.error(failure);
        }
        deliver(McpSchema.JSONRPCResponse.result(request.id(), result));
        return Mono.empty();
    }

    @Override
    public Mono<Void> closeGracefully() {
        closed = true;
        closeCount.incrementAndGet();
        return Mono.empty();
    }

    @Override
    public <T> T unmarshalFrom(Object data, TypeRef<T> typeRef) {
        return jsonMapper.convertValue(data, typeRef);
    }

    /** @return every method name the client sent, in order */
    List<String> receivedMethods() {
        return List.copyOf(receivedMethods);
    }

    /** @return the {@code tools/call} parameters the client sent, in order */
    List<Map<String, Object>> receivedToolCalls() {
        return List.copyOf(receivedToolCalls);
    }

    /** @return how often the transport was closed */
    int closeCount() {
        return closeCount.get();
    }

    boolean isClosed() {
        return closed;
    }

    FakeMcpTransport failPings(boolean value) {
        this.failPings = value;
        return this;
    }

    FakeMcpTransport failToolList(boolean value) {
        this.failToolList = value;
        return this;
    }

    FakeMcpTransport failInitialize(boolean value) {
        this.failInitialize = value;
        return this;
    }

    FakeMcpTransport protocolVersion(String value) {
        this.protocolVersionOverride = value;
        return this;
    }

    FakeMcpTransport instructions(String value) {
        this.instructions = value;
        return this;
    }

    FakeMcpTransport tools(List<Map<String, Object>> value) {
        this.tools = List.copyOf(value);
        return this;
    }

    FakeMcpTransport toolCallResult(Map<String, Object> value) {
        this.toolCallResult = value;
        return this;
    }

    /** Records a {@code tools/call} payload in the wire shape, whichever form the client sends. */
    private void recordToolCall(Object params) {
        if (params instanceof McpSchema.CallToolRequest call) {
            Map<String, Object> recorded = new LinkedHashMap<>();
            recorded.put("name", call.name());
            recorded.put("arguments", call.arguments());
            receivedToolCalls.add(recorded);
            return;
        }
        if (params instanceof Map<?, ?> map) {
            Map<String, Object> recorded = new LinkedHashMap<>();
            map.forEach((key, value) -> recorded.put(String.valueOf(key), value));
            receivedToolCalls.add(recorded);
        }
    }

    /** @return the answer of one request, as a plain JSON tree */
    private Object respond(McpSchema.JSONRPCRequest request) {
        return switch (request.method()) {
            case McpSchema.METHOD_INITIALIZE -> {
                if (failInitialize) {
                    throw new IllegalStateException("initialize rejected");
                }
                String requested = request.params() instanceof McpSchema.InitializeRequest initializeRequest
                        ? initializeRequest.protocolVersion()
                        : ProtocolVersions.MCP_2025_11_25;
                yield Map.of("protocolVersion", protocolVersionOverride == null ? requested : protocolVersionOverride,
                        "capabilities", Map.of("tools", Map.of()),
                        "serverInfo", Map.of("name", "fake-server", "version", "1.0"),
                        "instructions", instructions);
            }
            case McpSchema.METHOD_PING -> {
                if (failPings) {
                    throw new IllegalStateException("ping failed");
                }
                yield Map.of();
            }
            case McpSchema.METHOD_TOOLS_LIST -> {
                if (failToolList) {
                    throw new IllegalStateException("tools/list failed");
                }
                yield Map.of("tools", tools);
            }
            case McpSchema.METHOD_TOOLS_CALL -> toolCallResult;
            default -> Map.of();
        };
    }

    /** Hands one response to the client's inbound handler, exactly like a transport would. */
    private void deliver(McpSchema.JSONRPCMessage response) {
        Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>> inbound = handler;
        if (inbound == null) {
            throw new IllegalStateException("transport is not connected");
        }
        inbound.apply(Mono.just(response)).subscribe(ignored -> { }, ignored -> { });
    }

    private static String requestMethod(McpSchema.JSONRPCMessage message) {
        if (message instanceof McpSchema.JSONRPCNotification notification) {
            return notification.method();
        }
        return message.getClass().getSimpleName();
    }
}
