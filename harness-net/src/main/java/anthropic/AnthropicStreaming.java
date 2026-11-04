package anthropic;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.summit.core.conversation.api.ChatResponseEntity;
import com.summit.core.exception.NonRetriableModelException;
import com.summit.core.exception.RetriableModelException;
import com.summit.core.model.streaming.StreamingChatResponseHandler;
import com.summit.core.model.streaming.StreamingHandler;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;

/** Reads one Messages API stream and emits framework callbacks. */
final class AnthropicStreaming implements StreamingHandler {
    private enum State { ACTIVE, CANCELLED, FINISHED }

    private final HttpClient client;
    private final ObjectMapper mapper;
    private final StreamingChatResponseHandler handler;
    private final boolean returnThinking;
    private final AtomicReference<State> state = new AtomicReference<>(State.ACTIVE);
    private final AtomicReference<InputStream> body = new AtomicReference<>();
    private Thread worker;

    AnthropicStreaming(HttpClient client, ObjectMapper mapper, StreamingChatResponseHandler handler, boolean returnThinking) {
        this.client = client;
        this.mapper = mapper;
        this.handler = handler;
        this.returnThinking = returnThinking;
    }

    void start(HttpRequest request) {
        worker = Thread.ofVirtual().unstarted(() -> run(request));
        handler.onStreamStart(this);
        if (!isCancelled()) worker.start();
    }

    @Override
    public void cancel() {
        if (!state.compareAndSet(State.ACTIVE, State.CANCELLED)) return;
        worker.interrupt();
        InputStream input = body.getAndSet(null);
        if (input != null) {
            try {
                input.close();
            } catch (IOException ignored) {
                // The request is already cancelled.
            }
        }
    }

    @Override
    public boolean isCancelled() {
        return state.get() == State.CANCELLED;
    }

    private void run(HttpRequest request) {
        if (isCancelled()) return;
        ChatResponseEntity result;
        try {
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream input = response.body()) {
                body.set(input);
                if (isCancelled()) return;
                AnthropicChatModel.checkStatus(response.statusCode());
                result = read(input);
            } finally {
                body.set(null);
            }
        } catch (JsonProcessingException error) {
            fail(new NonRetriableModelException("Invalid Anthropic stream JSON", error));
            return;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            fail(new NonRetriableModelException("Anthropic stream interrupted", error));
            return;
        } catch (IOException error) {
            fail(new RetriableModelException("Anthropic stream transport failed", error));
            return;
        } catch (RuntimeException | Error error) {
            fail(error);
            return;
        }
        if (state.compareAndSet(State.ACTIVE, State.FINISHED)) handler.onFinalResponse(result);
    }

    private void fail(Throwable error) {
        if (state.compareAndSet(State.ACTIVE, State.FINISHED)) handler.onError(error);
    }

    private ChatResponseEntity read(InputStream input) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
        MessageBuffer message = new MessageBuffer();
        StringBuilder data = new StringBuilder();
        String line;
        while (!isCancelled() && (line = reader.readLine()) != null) {
            if (line.startsWith("data:")) {
                if (!data.isEmpty()) data.append('\n');
                int start = line.startsWith("data: ") ? 6 : 5;
                data.append(line, start, line.length());
            } else if (line.isEmpty() && !data.isEmpty() && !isCancelled()) {
                JsonNode event = mapper.readTree(data.toString());
                data.setLength(0);
                if (message.accept(event)) return message.finish();
            }
        }
        throw new RetriableModelException("Anthropic stream ended before message_stop", null);
    }

    private final class MessageBuffer {
        private final Map<Integer, Block> active = new HashMap<>();
        private final Map<Integer, ObjectNode> completed = new TreeMap<>();
        private ObjectNode response;
        private int nextToolIndex;

        boolean accept(JsonNode event) throws JsonProcessingException {
            switch (event.path("type").asText()) {
                case "message_start" -> {
                    if (response != null) throw invalid("duplicate message_start");
                    response = object(event.get("message")).deepCopy();
                }
                case "content_block_start" -> startBlock(event);
                case "content_block_delta" -> appendDelta(block(event), event.path("delta"));
                case "content_block_stop" -> stopBlock(event);
                case "message_delta" -> updateMessage(event);
                case "message_stop" -> { return true; }
                case "error" -> throw streamError(event.path("error").path("type").asText());
                default -> { }
            }
            return false;
        }

        private void startBlock(JsonNode event) {
            int index = index(event);
            ObjectNode content = object(event.get("content_block")).deepCopy();
            boolean tool = "tool_use".equals(content.path("type").asText());
            Block block = new Block(content, tool ? nextToolIndex++ : -1);
            if (completed.containsKey(index) || active.putIfAbsent(index, block) != null) {
                throw invalid("duplicate content block");
            }
            if (tool) {
                handler.onPartialToolCall(block.toolIndex, content.path("id").asText(),
                        content.path("name").asText(), null, AnthropicStreaming.this);
            } else {
                emitText(content.path("text").asText(), false);
                emitText(content.path("thinking").asText(), true);
            }
        }

        private Block block(JsonNode event) {
            Block block = active.get(index(event));
            if (block == null) throw invalid("content block is not active");
            return block;
        }

        private void appendDelta(Block block, JsonNode delta) {
            switch (delta.path("type").asText()) {
                case "text_delta" -> {
                    String part = delta.path("text").asText();
                    block.fragments.append(part);
                    emitText(part, false);
                }
                case "thinking_delta" -> {
                    String part = delta.path("thinking").asText();
                    block.fragments.append(part);
                    emitText(part, true);
                }
                case "signature_delta" -> block.content.put("signature", delta.path("signature").asText());
                case "input_json_delta" -> {
                    String part = delta.path("partial_json").asText();
                    if (block.toolIndex < 0) throw invalid("tool input delta outside a tool block");
                    block.fragments.append(part);
                    handler.onPartialToolCall(block.toolIndex, null, null, part, AnthropicStreaming.this);
                }
                default -> { }
            }
        }

        private void stopBlock(JsonNode event) throws JsonProcessingException {
            Block block = block(event);
            ObjectNode content = block.content;
            if (!block.fragments.isEmpty()) {
                switch (content.path("type").asText()) {
                    case "text" -> content.put("text", content.path("text").asText() + block.fragments);
                    case "thinking" -> content.put("thinking", content.path("thinking").asText() + block.fragments);
                    case "tool_use" -> content.set("input", object(mapper.readTree(block.fragments.toString())));
                    default -> { }
                }
            }
            int index = index(event);
            active.remove(index);
            completed.put(index, content);
        }

        private void updateMessage(JsonNode event) {
            if (response == null) throw invalid("message_delta before message_start");
            JsonNode stopReason = event.path("delta").get("stop_reason");
            if (stopReason != null && !stopReason.isNull()) response.set("stop_reason", stopReason);
            if (event.get("usage") instanceof ObjectNode usage) {
                response.withObjectProperty("usage").setAll(usage);
            }
        }

        ChatResponseEntity finish() throws JsonProcessingException {
            if (response == null || !active.isEmpty() || !response.hasNonNull("stop_reason")) {
                throw new RetriableModelException("Anthropic stream stopped with an incomplete message", null);
            }
            response.putArray("content").addAll(completed.values());
            return ResponseSerializer.serialize(response, mapper, returnThinking);
        }
    }

    private void emitText(String text, boolean thinking) {
        if (text.isEmpty() || isCancelled()) return;
        if (!thinking) handler.onPartialResponse(text, this);
        else if (returnThinking) handler.onPartialThinking(text, this);
    }

    private static int index(JsonNode event) {
        int index = event.path("index").asInt(-1);
        if (index < 0) throw invalid("missing content block index");
        return index;
    }

    private static ObjectNode object(JsonNode node) {
        if (node instanceof ObjectNode object) return object;
        throw invalid("expected an object");
    }

    private static NonRetriableModelException invalid(String message) {
        return new NonRetriableModelException("Invalid Anthropic stream: " + message, null);
    }

    private static RuntimeException streamError(String type) {
        String message = "Anthropic stream failed: " + type;
        return switch (type) {
            case "overloaded_error", "api_error", "rate_limit_error", "timeout_error" ->
                    new RetriableModelException(message, null);
            default -> new NonRetriableModelException(message, null);
        };
    }

    private static final class Block {
        private final ObjectNode content;
        private final int toolIndex;
        private final StringBuilder fragments = new StringBuilder();

        Block(ObjectNode content, int toolIndex) {
            this.content = content;
            this.toolIndex = toolIndex;
        }
    }
}
